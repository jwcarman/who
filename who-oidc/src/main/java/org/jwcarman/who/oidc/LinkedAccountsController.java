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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists and unlinks the caller's provider accounts: {@code GET /who/me/accounts} and {@code DELETE
 * /who/me/accounts/{id}}. Starting a link ({@code POST /who/me/accounts/{registrationId}}) is
 * {@link LinkStartFilter}'s.
 *
 * <p>The list is for any signed-in caller, a key or a bearer token included, oldest first. {@code
 * provider} is the client registration id whose issuer matches the account's, or the issuer itself
 * when none does; {@code linkedAt} is when the account was first linked, read from its time-ordered
 * id.
 *
 * <p>Unlinking is for a browser session only (an API key or bearer token, even beside the cookie,
 * is {@code 403}), needs a recent sign-in, and runs through the {@link LastCredentialGuard}, so
 * unlinking the last sign-in credential is {@code 409}. Only an account a configured registration
 * signs in with counts toward that rule (see {@link OidcSignInCredentialCounter}); one whose
 * registration was removed, or a JWT's issuer and subject, is unlinked without it. An account that
 * does not exist and one that belongs to someone else are both {@code 404}. Unlinking the account
 * the session signed in with is allowed and ends that session, which who re-resolves on every
 * request.
 */
@RestController
public class LinkedAccountsController {

  private final SignInRegistrations registrations;
  private final FederatedCredentialService credentials;
  private final LastCredentialGuard guard;
  private final WhoSessionSecurityContextRepository sessions;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the controller.
   *
   * @param registrations names each account's provider
   * @param credentials lists and unlinks the caller's accounts
   * @param guard keeps an identity from losing its last sign-in credential
   * @param sessions tells a browser session from a key or token
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to unlink an account
   */
  public LinkedAccountsController(
      SignInRegistrations registrations,
      FederatedCredentialService credentials,
      LastCredentialGuard guard,
      WhoSessionSecurityContextRepository sessions,
      Clock clock,
      Duration recentSignIn) {
    this.registrations = requireNonNull(registrations, "registrations must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * The caller's linked accounts, oldest first. Any signed-in caller may list: it changes nothing.
   *
   * @return the accounts
   */
  @GetMapping(WhoPaths.MAPPING + LinkStartFilter.ACCOUNTS)
  public List<LinkedAccountView> list() {
    UUID identityId = signedIn().getPrincipal().identity().id();
    return credentials.findAllFor(identityId).stream().map(this::view).toList();
  }

  /**
   * Unlinks one of the caller's accounts, unless it is their last way to sign in.
   *
   * @param id the account's id
   * @param request the request
   */
  @DeleteMapping(WhoPaths.MAPPING + LinkStartFilter.ACCOUNTS + "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(@PathVariable("id") String id, HttpServletRequest request) {
    WhoAuthenticationToken token = signedIn();
    // an API key or bearer token, even one ignored beside the session cookie, is not a browser
    if (!(sessions.caller(request, token) instanceof WhoCaller.Session current)) {
      throw new BrowserSessionRequiredException("Only a browser session can unlink an account");
    }
    // before looking at the id, so a stale session learns nothing about any account
    if (!current.isRecent(recentSignIn, clock.instant())) {
      throw new ReauthenticationRequiredException();
    }
    UUID identityId = token.getPrincipal().identity().id();
    // another identity's account and one that does not exist are the same answer; unlinking
    // itself checks no owner, so this is the check
    FederatedCredential account =
        parse(id)
            .flatMap(
                accountId ->
                    credentials.findAllFor(identityId).stream()
                        .filter(credential -> credential.id().equals(accountId))
                        .findFirst())
            .orElseThrow(LinkedAccountNotFoundException::new);
    if (!signsIn(account.issuer())) {
      // an account no registration signs in with is not counted, so it is never the last
      credentials.unlink(account.id());
      return;
    }
    guard.removeIfNotLast(
        identityId,
        () -> {
          credentials.unlink(account.id());
          return Boolean.TRUE;
        });
  }

  private static WhoAuthenticationToken signedIn() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      throw new NotSignedInException();
    }
    return token;
  }

  private LinkedAccountView view(FederatedCredential credential) {
    return new LinkedAccountView(
        credential.id().toString(),
        provider(credential.issuer()),
        credential.linkedAt().toString());
  }

  /** The registration whose issuer this is, else the issuer: a removed registration's accounts. */
  private String provider(String issuer) {
    return registrations.all().stream()
        .filter(registration -> registration.issuer().equals(issuer))
        .map(SignInRegistration::registrationId)
        .findFirst()
        .orElse(issuer);
  }

  /** Whether a configured registration signs in with this issuer's accounts. */
  private boolean signsIn(String issuer) {
    return registrations.all().stream()
        .anyMatch(registration -> registration.issuer().equals(issuer));
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
