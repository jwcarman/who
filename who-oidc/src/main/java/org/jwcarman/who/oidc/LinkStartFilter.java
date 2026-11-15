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
import java.time.Clock;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

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
  private final WhoExceptionHandoff handoff;
  private final Clock clock;

  LinkStartFilter(
      OidcAuthorizationRequests authorizationRequests,
      AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository,
      WhoExceptionHandoff handoff,
      Clock clock) {
    this.authorizationRequests =
        requireNonNull(authorizationRequests, "authorizationRequests must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !LINK_START.matches(request);
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
    String registrationId = LINK_START.matcher(request).getVariables().get("registrationId");
    Optional<OAuth2AuthorizationRequest> authorization =
        authorizationRequests.link(
            request, registrationId, token.getPrincipal().identity().id(), clock.instant());
    if (authorization.isEmpty()) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    repository.saveAuthorizationRequest(authorization.get(), request, response);
    response.sendRedirect(authorization.get().getAuthorizationRequestUri());
  }
}
