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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

/**
 * Turns a provider sign-in into a who session, or into a pending sign-up for an account who does
 * not know. A provider sign-in links only when it answers {@code POST /who/oidc/link/{id}}: a plain
 * one by someone already signed in never offers to. Spring Security's own token never reaches the
 * session: the security context is cleared after every outcome.
 */
public final class OidcSignInHandler
    implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  private static final Logger log = LoggerFactory.getLogger(OidcSignInHandler.class);

  private final SignInRegistrations registrations;
  private final WhoService whoService;
  private final FederatedCredentialRepository credentials;
  private final WhoSessionSecurityContextRepository sessions;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final PendingSignUps pendingSignUps;
  private final OidcRedirects redirects;
  private final WhoEventPublisher eventPublisher;
  private final LastSignInCookie lastSignInCookie;
  private final Clock clock;
  private final Duration signUpWindow;
  private final Duration linkWindow;
  private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

  /**
   * Creates the handler.
   *
   * @param registrations the registrations who signs in with
   * @param whoService resolves a credential to its identity
   * @param credentials finds the credential for an issuer and subject
   * @param sessions stores the who session
   * @param sessionStrategy the chain's session fixation protection and CSRF rotation
   * @param pendingSignUps keeps pending sign-ups in the session
   * @param redirects where the browser goes afterwards
   * @param eventPublisher where sign-in events are published
   * @param lastSignInCookie remembers the provider used, or null when turned off
   * @param clock stamps events and expiries
   * @param signUpWindow how long a pending sign-up lasts
   * @param linkWindow how long a started link lasts
   */
  public OidcSignInHandler(
      SignInRegistrations registrations,
      WhoService whoService,
      FederatedCredentialRepository credentials,
      WhoSessionSecurityContextRepository sessions,
      SessionAuthenticationStrategy sessionStrategy,
      PendingSignUps pendingSignUps,
      OidcRedirects redirects,
      WhoEventPublisher eventPublisher,
      LastSignInCookie lastSignInCookie,
      Clock clock,
      Duration signUpWindow,
      Duration linkWindow) {
    this.registrations = requireNonNull(registrations, "registrations must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.sessionStrategy = requireNonNull(sessionStrategy, "sessionStrategy must not be null");
    this.pendingSignUps = requireNonNull(pendingSignUps, "pendingSignUps must not be null");
    this.redirects = requireNonNull(redirects, "redirects must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.lastSignInCookie = lastSignInCookie;
    this.clock = requireNonNull(clock, "clock must not be null");
    this.signUpWindow = requireNonNull(signUpWindow, "signUpWindow must not be null");
    this.linkWindow = requireNonNull(linkWindow, "linkWindow must not be null");
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    try {
      handle(request, response, (OAuth2AuthenticationToken) authentication);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    try {
      log.debug("Provider sign-in failed", exception);
      fail(request, response, SignInFailureReason.PROVIDER_ERROR, null, null);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void handle(
      HttpServletRequest request, HttpServletResponse response, OAuth2AuthenticationToken token)
      throws IOException {
    Optional<SignInRegistration> registration =
        registrations.find(token.getAuthorizedClientRegistrationId());
    if (registration.isEmpty()) {
      fail(request, response, SignInFailureReason.PROVIDER_ERROR, null, null);
      return;
    }
    ProviderAccount account;
    try {
      account = ProviderAccount.from(token.getPrincipal(), registration.get());
    } catch (IllegalStateException e) {
      log.debug("Provider sign-in returned no subject", e);
      fail(request, response, SignInFailureReason.MISSING_CLAIMS, null, null);
      return;
    }
    UUID signedIn = sessions.currentSession(request).map(WhoSession::identityId).orElse(null);
    LinkIntent intent =
        token instanceof LinkingAuthenticationToken linking ? linking.intent() : null;
    boolean link = intent != null;
    if (link && !linkStillHolds(intent, signedIn)) {
      fail(request, response, SignInFailureReason.LINK_REFUSED, intent.identityId(), null);
      return;
    }
    Optional<FederatedCredential> credential =
        credentials.findByIssuerAndSubject(registration.get().issuer(), account.subject());
    if (credential.isEmpty()) {
      unknownAccount(request, response, account, signedIn, link);
      return;
    }
    UUID credentialId = credential.get().id();
    switch (whoService.resolveDetailed(credential.get())) {
      case Resolution.Resolved(WhoPrincipal principal)
          when signedIn != null && !signedIn.equals(principal.identity().id()) ->
          alreadyLinked(request, response);
      case Resolution.Resolved _ when link -> alreadyYours(request, response);
      case Resolution.Resolved(WhoPrincipal principal) ->
          signIn(request, response, principal, credentialId, account);
      case Resolution.NotLinked _ -> unknownAccount(request, response, account, signedIn, link);
      // a link to an account another identity holds, active or not, is already linked; a sign-in
      // with one is a failed sign-in by that identity
      case Resolution.Inactive _ when link -> alreadyLinked(request, response);
      case Resolution.NoIdentity _ when link -> alreadyLinked(request, response);
      case Resolution.Inactive(UUID identityId) ->
          fail(request, response, SignInFailureReason.INACTIVE_IDENTITY, identityId, credentialId);
      case Resolution.NoIdentity(UUID identityId) ->
          fail(request, response, SignInFailureReason.UNKNOWN_CREDENTIAL, identityId, credentialId);
    }
  }

  /**
   * A link answers only the identity that started it, still signed in, within {@code
   * who.oidc.link-window} of starting.
   */
  private boolean linkStillHolds(LinkIntent intent, UUID signedIn) {
    return intent.identityId().equals(signedIn) && !intent.expired(clock.instant(), linkWindow);
  }

  private void alreadyLinked(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    pendingSignUps.consume(request);
    redirect(request, response, redirects.failure(OidcRedirects.ALREADY_LINKED));
  }

  /**
   * An account who does not know: a pending link when the person asked to link, a pending sign-up
   * when no one is signed in, and nothing at all for a plain sign-in by someone signed in.
   */
  private void unknownAccount(
      HttpServletRequest request,
      HttpServletResponse response,
      ProviderAccount account,
      UUID signedIn,
      boolean link)
      throws IOException {
    if (signedIn != null && !link) {
      pendingSignUps.consume(request);
      redirect(request, response, redirects.failure(OidcRedirects.SIGNED_IN));
      return;
    }
    startPendingSignUp(request, response, account, signedIn);
  }

  /**
   * A link to an account the signed-in identity already has: nothing to do. The session keeps the
   * credential and sign-in time it had; a link is not a sign-in.
   */
  private void alreadyYours(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    pendingSignUps.consume(request);
    redirect(request, response, redirects.success());
  }

  private void signIn(
      HttpServletRequest request,
      HttpServletResponse response,
      WhoPrincipal principal,
      UUID credentialId,
      ProviderAccount account)
      throws IOException {
    WhoAuthenticationToken who =
        new WhoAuthenticationToken(principal, credentialId, SignInMethod.OIDC);
    sessionStrategy.onAuthentication(who, request, response);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(who);
    sessions.saveContext(context, request, response);
    pendingSignUps.consume(request);
    eventPublisher.publish(
        new SignInSucceeded(
            clock.instant(), SignInMethod.OIDC, principal.identity().id(), credentialId));
    if (lastSignInCookie != null) {
      lastSignInCookie.write(response, account.registration().registrationId());
    }
    redirect(request, response, redirects.success());
  }

  private void startPendingSignUp(
      HttpServletRequest request,
      HttpServletResponse response,
      ProviderAccount account,
      UUID signedIn)
      throws IOException {
    pendingSignUps.save(
        request, PendingSignUp.of(account, signedIn, clock.instant().plus(signUpWindow)));
    redirect(request, response, redirects.failure(OidcRedirects.NO_ACCOUNT));
  }

  private void fail(
      HttpServletRequest request,
      HttpServletResponse response,
      SignInFailureReason reason,
      UUID identityId,
      UUID credentialId)
      throws IOException {
    eventPublisher.publish(
        new SignInFailed(
            clock.instant(), SignInMethod.OIDC, reason, null, identityId, credentialId));
    redirect(request, response, redirects.failure(OidcRedirects.SIGNIN_FAILED));
  }

  private void redirect(HttpServletRequest request, HttpServletResponse response, String url)
      throws IOException {
    redirectStrategy.sendRedirect(request, response, url);
  }
}
