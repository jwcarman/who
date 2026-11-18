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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /who/me/passkeys} lists the caller's passkeys, oldest first; {@code DELETE
 * /who/me/passkeys/{credentialId}} removes one. Both are for a browser session only: an API key or
 * a bearer token, even beside a session cookie, is refused with {@code 403}. Registering a passkey
 * ({@code POST /who/me/passkeys/options}, {@code POST /who/me/passkeys}) is Spring's WebAuthn
 * filters'.
 *
 * <p>Removing a passkey needs a recent sign-in ({@link ReauthenticationRequiredException}, {@code
 * 401}), and runs through the {@link LastCredentialGuard}: removing the identity's last sign-in
 * credential is {@code 409}. A passkey that does not exist and one that belongs to someone else are
 * both {@code 404}, so an id reveals nothing about other identities. A passkey is named by its
 * WebAuthn credential id, never by its internal UUID, which is not shown.
 */
@RestController
public class PasskeysController {

  private final WhoSessionSecurityContextRepository sessionRepository;
  private final PasskeyService passkeys;
  private final LastCredentialGuard guard;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the controller.
   *
   * @param sessionRepository classifies the caller and gives the browser session
   * @param passkeys lists and revokes passkeys
   * @param guard keeps an identity from losing its last sign-in credential
   * @param clock the current time
   * @param recentSignIn how recently the session must have signed in to remove a passkey
   */
  public PasskeysController(
      WhoSessionSecurityContextRepository sessionRepository,
      PasskeyService passkeys,
      LastCredentialGuard guard,
      Clock clock,
      Duration recentSignIn) {
    this.sessionRepository =
        requireNonNull(sessionRepository, "sessionRepository must not be null");
    this.passkeys = requireNonNull(passkeys, "passkeys must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * The caller's passkeys, oldest first.
   *
   * @param request the request
   * @return the passkeys
   */
  @GetMapping(WhoPaths.MAPPING + PasskeyFilters.CREDENTIALS)
  public List<PasskeyView> list(HttpServletRequest request) {
    UUID identityId = identityOf(browser(request));
    return passkeys.list(identityId).stream().map(PasskeysController::view).toList();
  }

  /**
   * Removes one of the caller's passkeys, unless it is their last way to sign in.
   *
   * @param credentialId the passkey's WebAuthn credential id
   * @param request the request
   */
  @DeleteMapping(WhoPaths.MAPPING + PasskeyFilters.CREDENTIALS + "/{credentialId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(
      @PathVariable("credentialId") String credentialId, HttpServletRequest request) {
    WhoCaller.Session current = browser(request);
    // before looking at the id, so a stale session learns nothing about any passkey
    if (!current.isRecent(recentSignIn, clock.instant())) {
      throw new ReauthenticationRequiredException();
    }
    UUID identityId = identityOf(current);
    // another identity's passkey and one that does not exist are the same answer
    UUID passkeyId =
        passkeys
            .ownerOf(credentialId)
            .filter(identityId::equals)
            .flatMap(_ -> internalId(identityId, credentialId))
            .orElseThrow(PasskeyNotFoundException::new);
    guard.removeIfNotLast(
        identityId,
        () -> {
          passkeys.revoke(passkeyId);
          return Boolean.TRUE;
        });
  }

  private WhoCaller.Session browser(HttpServletRequest request) {
    WhoCaller caller =
        sessionRepository.caller(request, SecurityContextHolder.getContext().getAuthentication());
    if (caller instanceof WhoCaller.SignedOut) {
      throw new NotSignedInException();
    }
    // an API key or bearer token, even one ignored beside the session cookie, is not a browser
    if (!(caller instanceof WhoCaller.Session current)) {
      throw new BrowserSessionRequiredException("Only a browser session can manage passkeys");
    }
    return current;
  }

  private static UUID identityOf(WhoCaller.Session current) {
    return current.token().getPrincipal().identity().id();
  }

  /** Never the internal id: the credential id is what names a passkey. */
  private static PasskeyView view(PasskeySummary passkey) {
    return new PasskeyView(
        passkey.credentialId(),
        passkey.label(),
        passkey.created().toString(),
        passkey.lastUsed() == null ? null : passkey.lastUsed().toString());
  }

  /** The passkey's internal id, which only the owner's own list can name. */
  private Optional<UUID> internalId(UUID identityId, String credentialId) {
    return passkeys.list(identityId).stream()
        .filter(passkey -> passkey.credentialId().equals(credentialId))
        .map(PasskeySummary::id)
        .findFirst();
  }
}
