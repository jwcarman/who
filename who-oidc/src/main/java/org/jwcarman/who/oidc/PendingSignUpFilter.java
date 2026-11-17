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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoSignInFactors;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * The pending sign-up endpoints: {@code GET /who/oidc/pending} describes it for the confirm page,
 * {@code POST /who/oidc/pending/sign-up} confirms a sign-up (signed out), and {@code POST
 * /who/oidc/pending/link} links it to the identity that started it, within the recent sign-in
 * window. Each POST uses the pending sign-up up. A filter, not a controller, so a sign-up runs the
 * chain's session strategy. The paths shown are under the default root; each follows {@code
 * who.base-path}.
 */
public final class PendingSignUpFilter extends OncePerRequestFilter {

  /** Describes the pending sign-up, below the root. */
  static final String PENDING = "/oidc/pending";

  /** Confirms a sign-up, below the root. */
  static final String SIGN_UP = "/oidc/pending/sign-up";

  /** Confirms a link, below the root. */
  static final String LINK = "/oidc/pending/link";

  private final RequestMatcher pendingRequest;
  private final RequestMatcher signUpRequest;
  private final RequestMatcher linkRequest;
  private final OidcSignUpService signUpService;
  private final IdentityRepository identities;
  private final WhoSessionSecurityContextRepository sessions;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final PendingSignUps pendingSignUps;
  private final WhoExceptionHandoff handoff;
  private final WhoEventPublisher eventPublisher;
  private final LastSignInCookie lastSignInCookie;
  private final Clock clock;
  private final Duration linkWindow;

  /**
   * Creates the filter.
   *
   * @param paths where the endpoints live
   * @param signUpService confirms sign-ups and links
   * @param identities finds the identity signed in
   * @param sessions stores the who session
   * @param sessionStrategy the chain's session fixation protection and CSRF rotation
   * @param pendingSignUps keeps pending sign-ups in the session
   * @param handoff answers errors through the application's exception handling
   * @param eventPublisher where sign-in events are published
   * @param lastSignInCookie remembers the provider used, or null when turned off
   * @param clock the current time
   * @param linkWindow how recently the identity must have signed in to link an account
   */
  public PendingSignUpFilter(
      WhoPaths paths,
      OidcSignUpService signUpService,
      IdentityRepository identities,
      WhoSessionSecurityContextRepository sessions,
      SessionAuthenticationStrategy sessionStrategy,
      PendingSignUps pendingSignUps,
      WhoExceptionHandoff handoff,
      WhoEventPublisher eventPublisher,
      LastSignInCookie lastSignInCookie,
      Clock clock,
      Duration linkWindow) {
    requireNonNull(paths, "paths must not be null");
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
    this.pendingRequest = matchers.matcher(HttpMethod.GET, paths.of(PENDING));
    this.signUpRequest = matchers.matcher(HttpMethod.POST, paths.of(SIGN_UP));
    this.linkRequest = matchers.matcher(HttpMethod.POST, paths.of(LINK));
    this.signUpService = requireNonNull(signUpService, "signUpService must not be null");
    this.identities = requireNonNull(identities, "identities must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.sessionStrategy = requireNonNull(sessionStrategy, "sessionStrategy must not be null");
    this.pendingSignUps = requireNonNull(pendingSignUps, "pendingSignUps must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.lastSignInCookie = lastSignInCookie;
    this.clock = requireNonNull(clock, "clock must not be null");
    this.linkWindow = requireNonNull(linkWindow, "linkWindow must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !pendingRequest.matches(request)
        && !signUpRequest.matches(request)
        && !linkRequest.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (pendingRequest.matches(request)) {
      describe(request, response);
    } else if (signUpRequest.matches(request)) {
      signUp(request, response);
    } else {
      link(request, response);
    }
  }

  private void describe(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Optional<PendingSignUp> pending =
        pendingSignUps.current(request, signedIn().orElse(null), clock.instant());
    if (pending.isEmpty()) {
      handoff.handOff(
          request, response, new PendingSignUpNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("provider", pending.get().registrationId());
    body.put("displayName", pending.get().displayName());
    body.put("canSignUp", pending.get().createdFor() == null);
    body.put("canLink", pending.get().createdFor() != null);
    write(response, body);
  }

  private void signUp(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (signedIn().isPresent()) {
      handoff.handOff(request, response, new SignUpRefusedException(), HttpStatus.FORBIDDEN);
      return;
    }
    Optional<PendingSignUp> pending = pendingSignUps.current(request, null, clock.instant());
    if (pending.isEmpty()) {
      handoff.handOff(
          request, response, new PendingSignUpNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    pendingSignUps.consume(request);
    OidcSignUpService.SignedUp signedUp;
    try {
      signedUp = signUpService.signUp(pending.get());
    } catch (SignUpRefusedException e) {
      handoff.handOff(request, response, e, HttpStatus.FORBIDDEN);
      return;
    }
    UUID identityId = signedUp.principal().identity().id();
    WhoAuthenticationToken token =
        WhoAuthenticationToken.signedIn(
            signedUp.principal(), signedUp.credentialId(), SignInMethod.OIDC, clock.instant());
    sessionStrategy.onAuthentication(token, request, response);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    sessions.saveContext(context, request, response);
    eventPublisher.publish(
        new SignInSucceeded(
            clock.instant(), SignInMethod.OIDC, identityId, signedUp.credentialId()));
    if (lastSignInCookie != null) {
      lastSignInCookie.write(response, pending.get().registrationId());
    }
    write(response, Map.of("identityId", identityId.toString()));
  }

  private void link(HttpServletRequest request, HttpServletResponse response) throws IOException {
    Optional<UUID> signedIn = signedIn();
    Optional<WhoSession> session = sessions.currentSession(request);
    if (signedIn.isEmpty() || session.isEmpty()) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    Instant now = clock.instant();
    if (!WhoSignInFactors.isRecent(session.get().signedInAt(), linkWindow, now)) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    Optional<PendingSignUp> pending = pendingSignUps.current(request, signedIn.get(), now);
    Optional<Identity> identity = identities.findById(signedIn.get());
    if (pending.isEmpty() || identity.isEmpty()) {
      handoff.handOff(
          request, response, new PendingSignUpNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    pendingSignUps.consume(request);
    FederatedCredential credential;
    try {
      credential = signUpService.link(identity.get(), pending.get());
    } catch (AlreadyLinkedException e) {
      handoff.handOff(request, response, e, HttpStatus.CONFLICT);
      return;
    }
    write(response, Map.of("credentialId", credential.id().toString()));
  }

  /** The identity signed in, read through the security context, which checks it is still live. */
  private static Optional<UUID> signedIn() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication instanceof WhoAuthenticationToken token
        ? Optional.of(token.getPrincipal().identity().id())
        : Optional.empty();
  }

  private static void write(HttpServletResponse response, Map<String, Object> body)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(JSONObjectUtils.toJSONString(body));
  }
}
