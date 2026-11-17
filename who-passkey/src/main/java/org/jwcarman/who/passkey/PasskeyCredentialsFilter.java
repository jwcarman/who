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
package org.jwcarman.who.passkey;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.exception.LastCredentialException;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET <root>/me/passkeys} lists the caller's passkeys, oldest first; {@code DELETE
 * <root>/me/passkeys/{credentialId}} removes one. Both are for a browser session only: an API key
 * or a bearer token beside a session cookie is refused with {@code 403}.
 *
 * <p>Removing a passkey needs a recent sign-in ({@link ReauthenticationRequiredException}, {@code
 * 401}), and runs through the {@link LastCredentialGuard}: removing the identity's last sign-in
 * credential is {@code 409}. A passkey that does not exist and one that belongs to someone else are
 * both {@code 404}, so an id reveals nothing about other identities.
 *
 * <p>A passkey is named by its WebAuthn credential id, never by its internal UUID, which is not
 * shown. Errors are handed to the application through {@link WhoExceptionHandoff}; who writes no
 * error body.
 *
 * <p>It belongs in the security chain, after {@code ExceptionTranslationFilter}; it is not a
 * servlet filter of its own, so it must not be exposed as a bean.
 */
public class PasskeyCredentialsFilter extends OncePerRequestFilter {

  private final PathPatternRequestMatcher list;
  private final PathPatternRequestMatcher remove;
  private final WhoSessionSecurityContextRepository sessionRepository;
  private final PasskeyService passkeys;
  private final LastCredentialGuard guard;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the filter.
   *
   * @param paths where the endpoints live
   * @param sessionRepository classifies the caller and gives the browser session
   * @param passkeys lists and revokes passkeys
   * @param guard keeps an identity from losing its last sign-in credential
   * @param handoff hands errors to the application
   * @param jsonMapper writes the list
   * @param clock the current time
   * @param recentSignIn how recently the session must have signed in to remove a passkey
   */
  public PasskeyCredentialsFilter(
      WhoPaths paths,
      WhoSessionSecurityContextRepository sessionRepository,
      PasskeyService passkeys,
      LastCredentialGuard guard,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    String url = requireNonNull(paths, "paths must not be null").of(PasskeyFilters.CREDENTIALS);
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
    this.list = matchers.matcher(HttpMethod.GET, url);
    this.remove = matchers.matcher(HttpMethod.DELETE, url + "/{credentialId}");
    this.sessionRepository =
        requireNonNull(sessionRepository, "sessionRepository must not be null");
    this.passkeys = requireNonNull(passkeys, "passkeys must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !list.matches(request) && !remove.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    WhoCaller caller = sessionRepository.caller(request, authentication);
    if (caller instanceof WhoCaller.SignedOut) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    // an API key or bearer token, even one ignored beside the session cookie, is not a browser
    if (!(caller instanceof WhoCaller.Session current)) {
      handoff.handOff(
          request,
          response,
          new AccessDeniedException("Only a browser session can manage passkeys"),
          HttpStatus.FORBIDDEN);
      return;
    }
    UUID identityId = current.token().getPrincipal().identity().id();
    if (list.matches(request)) {
      list(response, identityId);
    } else {
      remove(request, response, identityId, current);
    }
  }

  private void list(HttpServletResponse response, UUID identityId) throws IOException {
    List<Map<String, Object>> body = passkeys.list(identityId).stream().map(this::view).toList();
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(jsonMapper.writeValueAsString(body));
  }

  /** Never the internal id: the credential id is what names a passkey. */
  private Map<String, Object> view(PasskeySummary passkey) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("credentialId", passkey.credentialId());
    view.put("label", passkey.label());
    view.put("created", passkey.created().toString());
    view.put("lastUsed", passkey.lastUsed() == null ? null : passkey.lastUsed().toString());
    return view;
  }

  private void remove(
      HttpServletRequest request,
      HttpServletResponse response,
      UUID identityId,
      WhoCaller.Session current)
      throws IOException {
    // before looking at the id, so a stale session learns nothing about any passkey
    if (!current.isRecent(recentSignIn, clock.instant())) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    String credentialId = remove.matcher(request).getVariables().get("credentialId");
    // another identity's passkey and one that does not exist are the same answer
    Optional<UUID> passkeyId =
        passkeys
            .ownerOf(credentialId)
            .filter(identityId::equals)
            .flatMap(owner -> internalId(identityId, credentialId));
    if (passkeyId.isEmpty()) {
      handoff.handOff(request, response, new PasskeyNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    try {
      guard.removeIfNotLast(
          identityId,
          () -> {
            passkeys.revoke(passkeyId.get());
            return Boolean.TRUE;
          });
    } catch (LastCredentialException e) {
      handoff.handOff(request, response, e, HttpStatus.CONFLICT);
      return;
    }
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  /** The passkey's internal id, which only the owner's own list can name. */
  private Optional<UUID> internalId(UUID identityId, String credentialId) {
    return passkeys.list(identityId).stream()
        .filter(passkey -> passkey.credentialId().equals(credentialId))
        .map(PasskeySummary::id)
        .findFirst();
  }
}
