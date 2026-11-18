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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * who's identity admin endpoints:
 *
 * <ul>
 *   <li>{@code GET /identities?status=&role=&email=&page=0&size=20} pages through identities,
 *       oldest first, as an {@link IdentityPage}. {@code status} is an {@link IdentityStatus} name;
 *       {@code role} a role's name, which an unknown role matches no identity by; {@code email} an
 *       address, matched as email sign-in matches it, which finds at most one identity and is
 *       {@code 400} without email sign-in. Given together, every filter must match. {@code size}
 *       runs from 1 to 100;
 *   <li>{@code GET /identities/{id}} answers one {@link IdentityView};
 *   <li>{@code PATCH /identities/{id}} with {@code {"status": "ACTIVE"}} reactivates the identity
 *       and with {@code {"status": "DISABLED"}} deactivates it, as {@link WhoService} does, and
 *       answers its {@link IdentityView}; any other status, {@code SUSPENDED} included, is {@code
 *       400}. The caller cannot deactivate itself: {@code 409};
 *   <li>{@code PUT} and {@code DELETE /identities/{id}/roles/{role}} assign and remove a role,
 *       {@code 204} whether or not anything changed. The caller may assign only a role whose every
 *       permission it holds itself, or it is {@code 403} (see {@link RoleGrants}); removing is not
 *       restricted by the role's permissions.
 * </ul>
 *
 * <p>Any who sign-in may call them: a browser session, a bearer token, or an API key. The {@code
 * GET}s need {@link WhoAdminPermissions#IDENTITIES_READ}, the rest {@link
 * WhoAdminPermissions#IDENTITIES_MANAGE}, among the request's permissions (for an API key, those
 * its scopes keep); without it, {@code 403}. A browser session (a who session cookie with no API
 * key or bearer token beside it) that changes anything must have signed in recently, or it is
 * {@code 401} and must sign in again; a bearer token or an API key need not. These are checked in
 * that order, signed out ({@code 401}) first, by {@link AdminGate}, before any identity, role or
 * body is looked at. Then an unknown identity, an id that is not a UUID and an unknown role are
 * {@code 404}. A change to an identity other than the caller's own that holds a permission the
 * caller does not, as who resolves its permissions whatever its status, is {@code 403} {@link
 * IdentityOutranksCallerException}, checked before the role-grant rule and before the body. A
 * deactivation or role removal that would leave no active identity holding {@link
 * WhoAdminPermissions#IDENTITIES_MANAGE} is {@code 409} {@link LastAdminException} (see {@link
 * LastAdminGuard}). A query or body who refuses is {@code 400}.
 */
@RestController
public class IdentitiesController {

  /** The page size when the request names none. */
  static final int DEFAULT_SIZE = 20;

  /** The largest page size. */
  static final int MAX_SIZE = 100;

  private static final String IDENTITIES = WhoPaths.ROOT + "/identities";
  private static final String IDENTITY = IDENTITIES + "/{id}";
  private static final String ROLE = IDENTITY + "/roles/{role}";

  private final IdentityRepository identities;
  private final WhoService whoService;
  private final RbacService rbac;
  private final @Nullable IdentityEmails emails;
  private final @Nullable LastCredentialGuard guard;
  private final LastAdminGuard lastAdmin;
  private final AdminGate gate;
  private final JsonMapper jsonMapper;

  /**
   * Creates the controller.
   *
   * @param identities pages through and reads identities
   * @param whoService deactivates and reactivates identities, and resolves their permissions
   * @param rbac reads, assigns and removes roles
   * @param emails the identities' addresses; null without email sign-in
   * @param guard counts each identity's sign-in credentials; null shows no counts
   * @param lastAdmin keeps the last identity that may manage identities
   * @param gate admits the caller
   * @param jsonMapper reads request bodies
   */
  public IdentitiesController(
      IdentityRepository identities,
      WhoService whoService,
      RbacService rbac,
      @Nullable IdentityEmails emails,
      @Nullable LastCredentialGuard guard,
      LastAdminGuard lastAdmin,
      AdminGate gate,
      JsonMapper jsonMapper) {
    this.identities = requireNonNull(identities, "identities must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.emails = emails;
    this.guard = guard;
    this.lastAdmin = requireNonNull(lastAdmin, "lastAdmin must not be null");
    this.gate = requireNonNull(gate, "gate must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  /**
   * One page of identities, oldest first.
   *
   * @param request the request, whose query names the filters, the page and its size
   * @return the page
   */
  @GetMapping(IDENTITIES)
  public IdentityPage page(HttpServletRequest request) {
    gate.admit(request, WhoAdminPermissions.IDENTITIES_READ, false);
    int page = intParameter(request, "page", 0);
    if (page < 0) {
      throw new InvalidAdminRequestException("page must not be negative");
    }
    int size = intParameter(request, "size", DEFAULT_SIZE);
    if (size < 1 || size > MAX_SIZE) {
      throw new InvalidAdminRequestException("size must be from 1 to " + MAX_SIZE);
    }
    Optional<IdentityStatus> status =
        parameter(request, "status").map(IdentitiesController::status);
    Optional<UUID> roleId = Optional.empty();
    Optional<String> roleName = parameter(request, "role");
    if (roleName.isPresent()) {
      Optional<Role> role = rbac.findRole(roleName.get());
      if (role.isEmpty()) {
        // a role that does not exist has no holders
        return new IdentityPage(List.of(), page, size, 0);
      }
      roleId = Optional.of(role.get().id());
    }
    Optional<Set<UUID>> ids = Optional.empty();
    Optional<String> address = parameter(request, "email");
    if (address.isPresent()) {
      if (emails == null) {
        throw new InvalidAdminRequestException(
            "email needs email sign-in, which this application does not have");
      }
      ids = Optional.of(emails.findIdentityId(address.get()).map(Set::of).orElse(Set.of()));
    }
    List<IdentityView> items =
        identities.findPage(status, roleId, ids, page, size).stream().map(this::view).toList();
    return new IdentityPage(items, page, size, identities.count(status, roleId, ids));
  }

  /**
   * One identity.
   *
   * @param id the identity's id
   * @param request the request
   * @return the identity
   */
  @GetMapping(IDENTITY)
  public IdentityView get(@PathVariable("id") String id, HttpServletRequest request) {
    gate.admit(request, WhoAdminPermissions.IDENTITIES_READ, false);
    return view(identity(id));
  }

  /**
   * Reactivates or deactivates an identity.
   *
   * @param id the identity's id
   * @param request the request, whose body is {@code {"status": "ACTIVE" | "DISABLED"}}
   * @return the identity as it is now
   */
  @PatchMapping(IDENTITY)
  public IdentityView changeStatus(@PathVariable("id") String id, HttpServletRequest request) {
    WhoPrincipal caller = gate.admit(request, WhoAdminPermissions.IDENTITIES_MANAGE, true);
    Identity target = identity(id);
    requireNotOutranked(caller, target);
    UUID targetId = target.id();
    JsonNode status = AdminBodies.readObject(jsonMapper, request).get("status");
    if (status == null || !status.isString()) {
      throw new InvalidAdminRequestException("The body needs status as a string");
    }
    boolean exists =
        switch (status.asString()) {
          case "ACTIVE" -> whoService.reactivate(targetId);
          case "DISABLED" -> {
            if (targetId.equals(caller.identity().id())) {
              throw new SelfDeactivationException();
            }
            yield lastAdmin.deactivate(targetId, () -> whoService.deactivate(targetId));
          }
          default -> throw new InvalidAdminRequestException("status must be ACTIVE or DISABLED");
        };
    if (!exists) {
      // deleted after it was found
      throw new IdentityNotFoundException();
    }
    return view(identities.findById(targetId).orElseThrow(IdentityNotFoundException::new));
  }

  /**
   * Assigns a role; already assigned is done.
   *
   * @param id the identity's id
   * @param roleName the role's name
   * @param request the request
   */
  @PutMapping(ROLE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void assignRole(
      @PathVariable("id") String id,
      @PathVariable("role") String roleName,
      HttpServletRequest request) {
    WhoPrincipal caller = gate.admit(request, WhoAdminPermissions.IDENTITIES_MANAGE, true);
    Identity identity = identity(id);
    Role role = role(roleName);
    requireNotOutranked(caller, identity);
    RoleGrants.requireMayGrant(rbac, caller.permissions(), List.of(role));
    try {
      rbac.assignRoleToIdentity(identity, role);
    } catch (IllegalArgumentException _) {
      // the role was deleted after it was found
      throw new UnknownRoleException();
    }
  }

  /**
   * Removes a role; not assigned is done.
   *
   * @param id the identity's id
   * @param roleName the role's name
   * @param request the request
   */
  @DeleteMapping(ROLE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeRole(
      @PathVariable("id") String id,
      @PathVariable("role") String roleName,
      HttpServletRequest request) {
    WhoPrincipal caller = gate.admit(request, WhoAdminPermissions.IDENTITIES_MANAGE, true);
    Identity identity = identity(id);
    Role role = role(roleName);
    requireNotOutranked(caller, identity);
    remove(identity, role);
  }

  /**
   * Removes the role. A role that carries the manage permission is removed through {@link
   * LastAdminGuard}, whose lock on that role also serializes two removals of it, so the check that
   * it is still assigned holds until the removal.
   */
  private void remove(Identity identity, Role role) {
    if (rbac.findPermissions(role).contains(WhoAdminPermissions.IDENTITIES_MANAGE)) {
      lastAdmin.removeRole(
          identity.id(),
          role.id(),
          () -> {
            if (rbac.findRolesForIdentity(identity.id()).contains(role)) {
              rbac.removeRoleFromIdentity(identity, role);
            }
            return Boolean.TRUE;
          });
      return;
    }
    try {
      rbac.removeRoleFromIdentity(identity, role);
    } catch (IllegalArgumentException _) {
      // not assigned, or no longer: removing it is done either way
    }
  }

  /**
   * Refuses a change to an identity holding a permission the caller does not, as who resolves the
   * identity's permissions whatever its status; acting on oneself always passes.
   */
  private void requireNotOutranked(WhoPrincipal caller, Identity target) {
    if (target.id().equals(caller.identity().id())) {
      return;
    }
    if (!caller.permissions().containsAll(whoService.permissionsOf(target))) {
      throw new IdentityOutranksCallerException();
    }
  }

  private IdentityView view(Identity identity) {
    UUID id = identity.id();
    List<String> roleNames =
        rbac.findRolesForIdentity(id).stream().map(Role::name).sorted().toList();
    List<String> addresses =
        emails == null ? List.of() : emails.addressesOf(id).stream().sorted().toList();
    Map<String, Long> credentials = guard == null ? Map.of() : guard.counts(id);
    return new IdentityView(
        id, identity.status(), identity.createdAt(), roleNames, addresses, credentials);
  }

  /** The identity the path names; an id that is not a UUID names none. */
  private Identity identity(String id) {
    try {
      return identities.findById(UUID.fromString(id)).orElseThrow(IdentityNotFoundException::new);
    } catch (IllegalArgumentException _) {
      throw new IdentityNotFoundException();
    }
  }

  private Role role(String name) {
    return rbac.findRole(name).orElseThrow(UnknownRoleException::new);
  }

  private static Optional<String> parameter(HttpServletRequest request, String name) {
    return Optional.ofNullable(request.getParameter(name)).filter(value -> !value.isBlank());
  }

  private static int intParameter(HttpServletRequest request, String name, int otherwise) {
    Optional<String> value = parameter(request, name);
    if (value.isEmpty()) {
      return otherwise;
    }
    try {
      return Integer.parseInt(value.get().trim());
    } catch (NumberFormatException _) {
      throw new InvalidAdminRequestException(name + " must be a whole number");
    }
  }

  private static IdentityStatus status(String value) {
    for (IdentityStatus status : IdentityStatus.values()) {
      if (status.name().equals(value)) {
        return status;
      }
    }
    throw new InvalidAdminRequestException("status must be ACTIVE, SUSPENDED or DISABLED");
  }
}
