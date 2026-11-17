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
package org.jwcarman.who.oidc;

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
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoSignInFactors;
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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jose.util.JSONArrayUtils;

/**
 * Lists and unlinks the caller's provider accounts: {@code GET /who/me/accounts} and {@code DELETE
 * /who/me/accounts/{id}}, beside {@link LinkStartFilter}'s {@code POST
 * /who/me/accounts/{registrationId}}.
 *
 * <p>The list is for any signed-in caller, oldest first, as {@code [{id, provider, linkedAt}]}.
 * {@code provider} is the client registration id whose issuer matches the account's, or the issuer
 * itself when none does; {@code linkedAt} is when the account was first linked, read from its
 * time-ordered id.
 *
 * <p>Unlinking is for a browser session only (an API key or bearer token beside the cookie is
 * {@code 403}), needs a recent sign-in, and runs through the {@link LastCredentialGuard}, so
 * unlinking the last sign-in credential is {@code 409}. An account that does not exist and one that
 * belongs to someone else are both {@code 404}. Unlinking the account the session signed in with is
 * allowed and ends that session, which who re-resolves on every request.
 */
final class LinkedAccountsFilter extends OncePerRequestFilter {

  private final RequestMatcher list;
  private final PathPatternRequestMatcher unlink;
  private final SignInRegistrations registrations;
  private final FederatedCredentialService credentials;
  private final LastCredentialGuard guard;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Clock clock;
  private final Duration recentSignIn;

  LinkedAccountsFilter(
      WhoPaths paths,
      SignInRegistrations registrations,
      FederatedCredentialService credentials,
      LastCredentialGuard guard,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Clock clock,
      Duration recentSignIn) {
    String url = requireNonNull(paths, "paths must not be null").of(LinkStartFilter.ACCOUNTS);
    this.list = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, url);
    this.unlink =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, url + "/{id}");
    this.registrations = requireNonNull(registrations, "registrations must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !list.matches(request) && !unlink.matches(request);
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
    UUID identityId = token.getPrincipal().identity().id();
    if (list.matches(request)) {
      list(response, identityId);
    } else {
      unlink(request, response, token, identityId);
    }
  }

  /** Any signed-in caller may list: it changes nothing. */
  private void list(HttpServletResponse response, UUID identityId) throws IOException {
    List<Map<String, Object>> body =
        credentials.findAllFor(identityId).stream().map(this::view).toList();
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(JSONArrayUtils.toJSONString(body));
  }

  private Map<String, Object> view(FederatedCredential credential) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", credential.id().toString());
    view.put("provider", provider(credential.issuer()));
    view.put("linkedAt", credential.linkedAt().toString());
    return view;
  }

  /** The registration whose issuer this is, else the issuer: a removed registration's accounts. */
  private String provider(String issuer) {
    return registrations.all().stream()
        .filter(registration -> registration.issuer().equals(issuer))
        .map(SignInRegistration::registrationId)
        .findFirst()
        .orElse(issuer);
  }

  private void unlink(
      HttpServletRequest request,
      HttpServletResponse response,
      WhoAuthenticationToken token,
      UUID identityId)
      throws IOException {
    // the browser session that signed this request in; an API key or bearer token beside the
    // session cookie, even one ignored, does not count
    Optional<WhoSession> current = sessions.browserSession(request, token);
    if (current.isEmpty()) {
      handoff.handOff(
          request,
          response,
          new AccessDeniedException("Only a browser session can unlink an account"),
          HttpStatus.FORBIDDEN);
      return;
    }
    // before looking at the id, so a stale session learns nothing about any account
    if (!WhoSignInFactors.isRecent(current.get().signedInAt(), recentSignIn, clock.instant())) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    // another identity's account and one that does not exist are the same answer; unlinking
    // itself checks no owner, so this is the check
    Optional<UUID> credentialId =
        parse(unlink.matcher(request).getVariables().get("id"))
            .filter(id -> credentials.isOwnedBy(id, identityId));
    if (credentialId.isEmpty()) {
      handoff.handOff(
          request, response, new LinkedAccountNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    try {
      guard.removeIfNotLast(
          identityId,
          () -> {
            credentials.unlink(credentialId.get());
            return Boolean.TRUE;
          });
    } catch (LastCredentialException e) {
      handoff.handOff(request, response, e, HttpStatus.CONFLICT);
      return;
    }
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
