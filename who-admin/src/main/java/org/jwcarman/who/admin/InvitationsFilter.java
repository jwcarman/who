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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.emaillink.EmailAddressTakenException;
import org.jwcarman.who.emaillink.InvalidInvitationException;
import org.jwcarman.who.emaillink.Invitation;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /invitations} with {@code {"email": "...", "roles": ["..."]}} invites the address
 * through {@link InvitationService#invite} and answers {@code 201} with {@code {identityId,
 * linkSent}}; {@code roles} may be left out for none. The invitation stands when the link is not
 * sent: {@code linkSent} is {@code false}.
 *
 * <p>It needs {@link WhoAdminPermissions#IDENTITIES_MANAGE} and, from a browser session, a recent
 * sign-in, checked as every admin endpoint checks them (see {@link AdminGate}), before the body is
 * read. Then a body that is not that JSON, a missing or malformed address and a role that does not
 * exist are {@code 400}; a role carrying a permission the caller does not hold is {@code 403} (see
 * {@link RoleGrants}); and an address that is already an identity's is {@code 409}, as is one a
 * concurrent invite of the same address took first. Anything else {@link InvitationService#invite}
 * throws, such as an exception from the application's {@code IdentityInvited} listener, goes to the
 * application's exception handling, so its {@code @RestControllerAdvice} or the exception's own
 * {@code @ResponseStatus} answers it; otherwise it is {@code 500}. Nothing is created in any of
 * these cases.
 */
final class InvitationsFilter extends OncePerRequestFilter {

  private final PathPatternRequestMatcher invite;
  private final InvitationService invitations;
  private final RbacService rbac;
  private final AdminGate gate;
  private final JsonMapper jsonMapper;

  InvitationsFilter(
      WhoPaths paths,
      InvitationService invitations,
      RbacService rbac,
      AdminGate gate,
      JsonMapper jsonMapper) {
    requireNonNull(paths, "paths must not be null");
    this.invite =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, paths.of("/invitations"));
    this.invitations = requireNonNull(invitations, "invitations must not be null");
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.gate = requireNonNull(gate, "gate must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !invite.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<WhoPrincipal> caller =
        gate.admit(request, response, WhoAdminPermissions.IDENTITIES_MANAGE, true)
            .map(WhoAuthenticationToken::getPrincipal);
    if (caller.isEmpty()) {
      return;
    }
    try {
      JsonNode body = AdminJson.readObject(jsonMapper, request);
      String email = email(body);
      Set<String> roleNames = roleNames(body);
      RoleGrants.requireMayGrant(rbac, caller.get().permissions(), roles(roleNames));
      Optional<Invitation> invitation = invite(request, response, email, roleNames);
      if (invitation.isEmpty()) {
        return;
      }
      AdminJson.write(
          response,
          HttpServletResponse.SC_CREATED,
          new Created(invitation.get().identityId(), invitation.get().linkSent()));
    } catch (InvalidAdminRequestException
        | InvalidInvitationException
        | RoleGrantForbiddenException
        | EmailAddressTakenException e) {
      // each carries its own status
      gate.refuse(request, response, e);
    }
  }

  /**
   * Invites, or answers why not: an address taken by a concurrent invite of the same address is
   * {@code 409} {@link EmailAddressTakenException}, as one taken beforehand is; anything else that
   * escapes {@link InvitationService#invite}, such as an {@code IdentityInvited} listener's
   * exception, goes to the application's exception handling, {@code 500} if nothing there answers
   * it and it carries no status of its own.
   */
  private Optional<Invitation> invite(
      HttpServletRequest request, HttpServletResponse response, String email, Set<String> roles)
      throws IOException {
    try {
      return Optional.of(invitations.invite(email, roles));
    } catch (InvalidInvitationException | EmailAddressTakenException e) {
      // each carries its own status
      gate.refuse(request, response, e);
      return Optional.empty();
    } catch (DuplicateKeyException _) {
      // the same address invited concurrently: the database's unique key decided which one won
      gate.refuse(request, response, new EmailAddressTakenException());
      return Optional.empty();
    } catch (RuntimeException e) {
      gate.fail(request, response, e);
      return Optional.empty();
    }
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

  /**
   * The answer to an invitation.
   *
   * @param identityId the invited identity
   * @param linkSent whether its sign-in link was sent
   */
  record Created(UUID identityId, boolean linkSent) {}
}
