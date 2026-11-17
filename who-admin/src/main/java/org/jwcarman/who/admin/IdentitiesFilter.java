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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoSignInFactors;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * who's admin endpoints:
 *
 * <ul>
 *   <li>{@code GET /identities?status=&role=&email=&page=0&size=20} pages through identities,
 *       oldest first, as {@code {items: [IdentityView], page, size, total}}. {@code status} is an
 *       {@link IdentityStatus} name; {@code role} a role's name, an unknown one {@code 404}; {@code
 *       email} an address, matched as email sign-in matches it, which finds at most one identity
 *       and is {@code 400} without email sign-in. Given together, every filter must match. {@code
 *       size} runs from 1 to 100;
 *   <li>{@code GET /identities/{id}} answers one {@link IdentityView};
 *   <li>{@code PATCH /identities/{id}} with {@code {"status": "ACTIVE"}} reactivates the identity
 *       and with {@code {"status": "INACTIVE"}} deactivates it, as {@link WhoService} does, and
 *       answers its {@link IdentityView}. The caller cannot deactivate itself: {@code 409};
 *   <li>{@code PUT} and {@code DELETE /identities/{id}/roles/{role}} assign and remove a role,
 *       {@code 204} whether or not anything changed;
 *   <li>{@code GET /roles} lists every role as {@code [{name, permissions}]}, by name.
 * </ul>
 *
 * <p>Any who sign-in may call them: a browser session, a bearer token, or an API key. The {@code
 * GET}s need {@link WhoAdminPermissions#IDENTITIES_READ}, the rest {@link
 * WhoAdminPermissions#IDENTITIES_MANAGE}, among the request's permissions (for an API key, those
 * its scopes keep); without it, {@code 403}. A browser session (a who session cookie with no API
 * key or bearer token beside it) that changes anything must have signed in recently, or it is
 * {@code 401} and must sign in again; a bearer token or an API key need not. These are checked in
 * that order, signed out ({@code 401}) first, before any identity, role or body is looked at. Then
 * an unknown identity, an id that is not a UUID and an unknown role are {@code 404}, and a query or
 * body who refuses is {@code 400}.
 */
final class IdentitiesFilter extends OncePerRequestFilter {

  /** The largest body read, in bytes. */
  static final int MAX_BODY_BYTES = 8192;

  /** The page size when the request names none. */
  static final int DEFAULT_SIZE = 20;

  /** The largest page size. */
  static final int MAX_SIZE = 100;

  private static final String IDENTITY = "/identities/{id}";

  private final PathPatternRequestMatcher list;
  private final PathPatternRequestMatcher read;
  private final PathPatternRequestMatcher patch;
  private final PathPatternRequestMatcher assign;
  private final PathPatternRequestMatcher unassign;
  private final PathPatternRequestMatcher roles;
  private final IdentityRepository identities;
  private final WhoService whoService;
  private final RbacService rbac;
  private final @Nullable IdentityEmails emails;
  private final @Nullable LastCredentialGuard guard;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  IdentitiesFilter(
      WhoPaths paths,
      IdentityRepository identities,
      WhoService whoService,
      RbacService rbac,
      @Nullable IdentityEmails emails,
      @Nullable LastCredentialGuard guard,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    requireNonNull(paths, "paths must not be null");
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
    this.list = matchers.matcher(HttpMethod.GET, paths.of("/identities"));
    this.read = matchers.matcher(HttpMethod.GET, paths.of(IDENTITY));
    this.patch = matchers.matcher(HttpMethod.PATCH, paths.of(IDENTITY));
    this.assign = matchers.matcher(HttpMethod.PUT, paths.of(IDENTITY + "/roles/{role}"));
    this.unassign = matchers.matcher(HttpMethod.DELETE, paths.of(IDENTITY + "/roles/{role}"));
    this.roles = matchers.matcher(HttpMethod.GET, paths.of("/roles"));
    this.identities = requireNonNull(identities, "identities must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.emails = emails;
    this.guard = guard;
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !list.matches(request)
        && !read.matches(request)
        && !roles.matches(request)
        && !changes(request);
  }

  /** Whether the request changes an identity, so needs the manage permission. */
  private boolean changes(HttpServletRequest request) {
    return patch.matches(request) || assign.matches(request) || unassign.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    boolean changes = changes(request);
    String needed =
        changes ? WhoAdminPermissions.IDENTITIES_MANAGE : WhoAdminPermissions.IDENTITIES_READ;
    if (!token.getPrincipal().permissions().contains(needed)) {
      handoff.handOff(
          request, response, new PermissionRequiredException(needed), HttpStatus.FORBIDDEN);
      return;
    }
    if (changes && staleBrowser(request, token)) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    try {
      answer(request, response, token.getPrincipal().identity().id());
    } catch (IdentityNotFoundException
        | UnknownRoleException
        | SelfDeactivationException
        | InvalidAdminRequestException e) {
      // each carries its own status; the fallback is never used
      handoff.handOff(request, response, e, HttpStatus.BAD_REQUEST);
    }
  }

  /** A browser session whose sign-in is no longer recent; a bearer token or an API key is not. */
  private boolean staleBrowser(HttpServletRequest request, WhoAuthenticationToken token) {
    return sessions
        .browserSession(request, token)
        .filter(
            session ->
                !WhoSignInFactors.isRecent(session.signedInAt(), recentSignIn, clock.instant()))
        .isPresent();
  }

  private void answer(HttpServletRequest request, HttpServletResponse response, UUID callerId)
      throws IOException {
    if (list.matches(request)) {
      writeJson(response, HttpServletResponse.SC_OK, page(request));
    } else if (read.matches(request)) {
      writeJson(response, HttpServletResponse.SC_OK, view(identity(read, request)));
    } else if (roles.matches(request)) {
      writeJson(response, HttpServletResponse.SC_OK, roles());
    } else if (patch.matches(request)) {
      writeJson(response, HttpServletResponse.SC_OK, changeStatus(request, callerId));
    } else if (assign.matches(request)) {
      Identity identity = identity(assign, request);
      try {
        rbac.assignRoleToIdentity(identity, role(assign, request));
      } catch (IllegalArgumentException _) {
        // the role was deleted after it was found
        throw new UnknownRoleException();
      }
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    } else {
      Identity identity = identity(unassign, request);
      try {
        rbac.removeRoleFromIdentity(identity, role(unassign, request));
      } catch (IllegalArgumentException _) {
        // not assigned, or no longer: removing it is done either way
      }
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
  }

  private Page page(HttpServletRequest request) {
    int page = intParameter(request, "page", 0);
    if (page < 0) {
      throw new InvalidAdminRequestException("page must not be negative");
    }
    int size = intParameter(request, "size", DEFAULT_SIZE);
    if (size < 1 || size > MAX_SIZE) {
      throw new InvalidAdminRequestException("size must be from 1 to " + MAX_SIZE);
    }
    Optional<IdentityStatus> status = parameter(request, "status").map(IdentitiesFilter::status);
    Optional<Set<UUID>> ids = Optional.empty();
    Optional<String> roleName = parameter(request, "role");
    if (roleName.isPresent()) {
      Role role = rbac.findRole(roleName.get()).orElseThrow(UnknownRoleException::new);
      ids = Optional.of(Set.copyOf(rbac.findIdentityIdsWithRole(role)));
    }
    Optional<String> address = parameter(request, "email");
    if (address.isPresent()) {
      if (emails == null) {
        throw new InvalidAdminRequestException(
            "email needs email sign-in, which this application does not have");
      }
      Set<UUID> owner = emails.findIdentityId(address.get()).map(Set::of).orElse(Set.of());
      ids = Optional.of(ids.map(known -> intersection(known, owner)).orElse(owner));
    }
    List<IdentityView> items =
        identities.findPage(status, ids, page, size).stream().map(this::view).toList();
    return new Page(items, page, size, identities.count(status, ids));
  }

  private static Set<UUID> intersection(Set<UUID> a, Set<UUID> b) {
    Set<UUID> both = new HashSet<>(a);
    both.retainAll(b);
    return both;
  }

  private IdentityView changeStatus(HttpServletRequest request, UUID callerId) throws IOException {
    UUID id = identity(patch, request).id();
    JsonNode status = readBody(request).get("status");
    if (status == null || !status.isString()) {
      throw new InvalidAdminRequestException("The body needs status as a string");
    }
    boolean exists =
        switch (status.asString()) {
          case "ACTIVE" -> whoService.reactivate(id);
          case "INACTIVE" -> {
            if (id.equals(callerId)) {
              throw new SelfDeactivationException();
            }
            yield whoService.deactivate(id);
          }
          default -> throw new InvalidAdminRequestException("status must be ACTIVE or INACTIVE");
        };
    if (!exists) {
      // deleted after it was found
      throw new IdentityNotFoundException();
    }
    return view(identities.findById(id).orElseThrow(IdentityNotFoundException::new));
  }

  private List<RoleView> roles() {
    return rbac.listRoles().stream()
        .map(
            role ->
                new RoleView(role.name(), rbac.findPermissions(role).stream().sorted().toList()))
        .toList();
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
  private Identity identity(PathPatternRequestMatcher matcher, HttpServletRequest request) {
    String id = matcher.matcher(request).getVariables().get("id");
    try {
      return identities.findById(UUID.fromString(id)).orElseThrow(IdentityNotFoundException::new);
    } catch (IllegalArgumentException _) {
      throw new IdentityNotFoundException();
    }
  }

  private Role role(PathPatternRequestMatcher matcher, HttpServletRequest request) {
    return rbac.findRole(matcher.matcher(request).getVariables().get("role"))
        .orElseThrow(UnknownRoleException::new);
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

  private JsonNode readBody(HttpServletRequest request) throws IOException {
    // The servlet container owns the request stream and closes it; who must not.
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new InvalidAdminRequestException("The body is too large");
    }
    byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new InvalidAdminRequestException("The body is too large");
    }
    JsonNode node;
    try {
      node = jsonMapper.readTree(bytes);
    } catch (JacksonException _) {
      throw new InvalidAdminRequestException("The body must be a JSON object");
    }
    if (node == null || !node.isObject()) {
      throw new InvalidAdminRequestException("The body must be a JSON object");
    }
    return node;
  }

  private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(jsonMapper.writeValueAsString(body));
  }

  /**
   * One page of identities.
   *
   * @param items the identities on it, oldest first
   * @param page its zero-based number
   * @param size the most identities a page holds
   * @param total how many identities match, on every page
   */
  record Page(List<IdentityView> items, int page, int size, long total) {}

  /**
   * A role as {@code GET /roles} shows it.
   *
   * @param name the role's name
   * @param permissions its permissions, sorted
   */
  record RoleView(String name, List<String> permissions) {}
}
