/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.who.admin;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.emaillink.InvalidInvitationException;
import org.jwcarman.who.emaillink.Invitation;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /invitations} with {@code {"email": "...", "roles": ["..."]}} invites the address
 * through {@link InvitationService#invite} and answers {@code 201} with a {@link
 * CreatedInvitation}; {@code roles} may be left out for none. The invitation stands when the link
 * is not sent: {@code linkSent} is {@code false}.
 *
 * <p>It needs {@link WhoAdminPermissions#IDENTITIES_MANAGE} and, from a browser session, a recent
 * sign-in, checked as every admin endpoint checks them (see {@link AdminGate}), before the body is
 * read. Then a body that is not that JSON, a missing or malformed address and a role that does not
 * exist are {@code 400}; a role carrying a permission the caller does not hold is {@code 403} (see
 * {@link RoleGrants}); and an address that is already an identity's is {@code 409}, as is one a
 * concurrent invite of the same address took first. Anything else {@link InvitationService#invite}
 * throws, such as an exception from the application's {@code IdentityInvited} listener, is answered
 * with the exception's own {@code @ResponseStatus}, else {@code 500}. Nothing is created in any of
 * these cases.
 */
@RestController
public class InvitationsController {

  private final InvitationService invitations;
  private final RbacService rbac;
  private final AdminGate gate;
  private final JsonMapper jsonMapper;

  /**
   * Creates the controller.
   *
   * @param invitations invites the address
   * @param rbac finds the roles named and reads their permissions
   * @param gate admits the caller
   * @param jsonMapper reads request bodies
   */
  public InvitationsController(
      InvitationService invitations, RbacService rbac, AdminGate gate, JsonMapper jsonMapper) {
    this.invitations = requireNonNull(invitations, "invitations must not be null");
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.gate = requireNonNull(gate, "gate must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  /**
   * Invites an address.
   *
   * @param request the request, whose body is {@code {"email": "...", "roles": ["..."]}}
   * @return the invited identity and whether its sign-in link was sent
   */
  @PostMapping(WhoPaths.MAPPING + "/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedInvitation invite(HttpServletRequest request) {
    WhoPrincipal caller = gate.admit(request, WhoAdminPermissions.IDENTITIES_MANAGE, true);
    JsonNode body = AdminBodies.readObject(jsonMapper, request);
    String email = email(body);
    Set<String> roleNames = roleNames(body);
    RoleGrants.requireMayGrant(rbac, caller.permissions(), roles(roleNames));
    Invitation invitation = invitations.invite(email, roleNames);
    return new CreatedInvitation(invitation.identityId(), invitation.linkSent());
  }

  private static String email(JsonNode body) {
    JsonNode email = body.get("email");
    if (email == null || !email.isString()) {
      throw new InvalidAdminRequestException("The body needs email as a string");
    }
    return email.asString();
  }

  private static Set<String> roleNames(JsonNode body) {
    JsonNode roles = body.get("roles");
    if (roles == null || roles.isNull()) {
      return Set.of();
    }
    if (!roles.isArray()) {
      throw new InvalidAdminRequestException("roles must be an array of role names");
    }
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode role : roles) {
      if (!role.isString()) {
        throw new InvalidAdminRequestException("roles must be an array of role names");
      }
      names.add(role.asString());
    }
    return names;
  }

  /** The roles named, each of which must exist: a role in the body is not a resource, so 400. */
  private List<Role> roles(Set<String> names) {
    List<Role> roles = new ArrayList<>(names.size());
    for (String name : names) {
      roles.add(
          rbac.findRole(name).orElseThrow(() -> new InvalidInvitationException("No such role")));
    }
    return roles;
  }
}
