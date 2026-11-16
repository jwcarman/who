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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoSignInFactors;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Starts a link: {@code POST /who/oidc/link/{registrationId}} by someone signed in sends them to
 * the provider with a request that remembers, on the server, that it is a link and for whom. A
 * POST, so the chain's CSRF protection means only the application's own pages can start one.
 */
final class LinkStartFilter extends OncePerRequestFilter {

  /** Starts a link through the registration named in the path. */
  static final String LINK_START_URL = PendingSignUpFilter.LINK_URL + "/{registrationId}";

  private static final RequestMatcher LINK_START =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LINK_START_URL);

  private final OidcAuthorizationRequests authorizationRequests;
  private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Clock clock;
  private final Duration recentSignIn;

  LinkStartFilter(
      OidcAuthorizationRequests authorizationRequests,
      AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Clock clock,
      Duration recentSignIn) {
    this.authorizationRequests =
        requireNonNull(authorizationRequests, "authorizationRequests must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !LINK_START.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<WhoSession> session = browserSignIn(request);
    if (session.isEmpty()) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    Instant now = clock.instant();
    // checked again at confirmation; here so a stale session fails before the trip to the provider
    if (!WhoSignInFactors.isRecent(session.get().signedInAt(), recentSignIn, now)) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    String registrationId = LINK_START.matcher(request).getVariables().get("registrationId");
    Optional<OAuth2AuthorizationRequest> authorization =
        authorizationRequests.link(request, registrationId, session.get().identityId(), now);
    if (authorization.isEmpty()) {
      handoff.handOff(request, response, new UnknownProviderException(), HttpStatus.NOT_FOUND);
      return;
    }
    repository.saveAuthorizationRequest(authorization.get(), request, response);
    String providerUrl = authorization.get().getAuthorizationRequestUri();
    if (wantsJson(request)) {
      // a single-page app's fetch, with the CSRF header: it cannot follow a redirect to another
      // site, so it is told where to go and goes there itself
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(JSONObjectUtils.toJSONString(Map.of("redirectUrl", providerUrl)));
      return;
    }
    response.sendRedirect(providerUrl);
  }

  /** Asked for JSON and not for a page: a browser's own form submission always accepts HTML. */
  private static boolean wantsJson(HttpServletRequest request) {
    List<MediaType> accepted;
    try {
      accepted = MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT));
    } catch (InvalidMediaTypeException _) {
      return false;
    }
    return accepted.stream().anyMatch(MediaType.APPLICATION_JSON::equalsTypeAndSubtype)
        && accepted.stream().noneMatch(MediaType.TEXT_HTML::equalsTypeAndSubtype);
  }

  /**
   * The identity signed in to this browser's who session. A caller signed in some other way, an API
   * key or a bearer token, has no session for the provider's callback to finish the link in.
   */
  private Optional<WhoSession> browserSignIn(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      return Optional.empty();
    }
    UUID identityId = token.getPrincipal().identity().id();
    return sessions
        .currentSession(request)
        .filter(session -> session.identityId().equals(identityId));
  }
}
