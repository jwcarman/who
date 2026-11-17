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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Starts a link: {@code POST /who/me/accounts/{registrationId}} by someone signed in sends them to
 * the provider with a request that remembers, on the server, that it is a link and for whom. A
 * POST, so the chain's CSRF protection means only the application's own pages can start one.
 */
final class LinkStartFilter extends OncePerRequestFilter {

  /** The signed-in person's linked accounts: {@code POST <root>/me/accounts/{registrationId}}. */
  static final String ACCOUNTS = "/me/accounts";

  private final RequestMatcher linkStart;
  private final OidcAuthorizationRequests authorizationRequests;
  private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Clock clock;
  private final Duration recentSignIn;

  LinkStartFilter(
      WhoPaths paths,
      OidcAuthorizationRequests authorizationRequests,
      AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Clock clock,
      Duration recentSignIn) {
    // starts a link through the registration named in the path
    this.linkStart =
        PathPatternRequestMatcher.withDefaults()
            .matcher(
                HttpMethod.POST,
                requireNonNull(paths, "paths must not be null").of(ACCOUNTS + "/{registrationId}"));
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
    return !linkStart.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    WhoCaller caller =
        sessions.caller(request, SecurityContextHolder.getContext().getAuthentication());
    if (caller instanceof WhoCaller.SignedOut) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    if (!(caller instanceof WhoCaller.Session session)) {
      // an API key or bearer token, even one ignored beside the session cookie: not a browser
      handoff.handOff(
          request,
          response,
          new AccessDeniedException("Only a browser session can link an account"),
          HttpStatus.FORBIDDEN);
      return;
    }
    Instant now = clock.instant();
    // checked again at confirmation; here so a stale session fails before the trip to the provider
    if (!session.isRecent(recentSignIn, now)) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    String registrationId = linkStart.matcher(request).getVariables().get("registrationId");
    Optional<OAuth2AuthorizationRequest> authorization =
        authorizationRequests.link(request, registrationId, session.session().identityId(), now);
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
}
