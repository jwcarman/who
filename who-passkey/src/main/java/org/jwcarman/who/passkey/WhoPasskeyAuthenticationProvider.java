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
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.NoIdentity;
import org.jwcarman.who.core.service.Resolution.NotLinked;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

/**
 * Signs in with a passkey: verifies the assertion through the relying party, maps the verified
 * credential to who's credential id, and resolves it to an active identity.
 *
 * <p>Every failure, whether the assertion does not verify, the credential is unknown, the identity
 * is not active, or anything else goes wrong, is the same {@link BadCredentialsException} with the
 * message {@code invalid_credential} and no cause. A failure whose reason who can tell publishes
 * {@link SignInFailed} with that reason ({@link SignInFailureReason#INVALID_ASSERTION} or {@link
 * SignInFailureReason#INACTIVE_IDENTITY}) and logs it at DEBUG. A successful sign-in is not
 * published here: the returned token carries {@link SignInMethod#PASSKEY}, and who's success
 * handler publishes it once the session is saved.
 */
public class WhoPasskeyAuthenticationProvider implements AuthenticationProvider {

  private static final Logger log = LoggerFactory.getLogger(WhoPasskeyAuthenticationProvider.class);

  static final String INVALID_CREDENTIAL = "invalid_credential";

  private final WebAuthnRelyingPartyOperations rpOperations;
  private final JdbcWhoPasskeyCredentialRepository credentialRepository;
  private final WhoService whoService;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock = Clock.systemUTC();

  /**
   * Creates a provider that publishes no events.
   *
   * @param rpOperations verifies assertions; the same instance Spring's WebAuthn filters use
   * @param credentialRepository maps WebAuthn credential ids to who's credential ids
   * @param whoService resolves credentials to principals
   */
  public WhoPasskeyAuthenticationProvider(
      WebAuthnRelyingPartyOperations rpOperations,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      WhoService whoService) {
    this(rpOperations, credentialRepository, whoService, WhoEventPublisher.NOOP);
  }

  /**
   * Creates the provider.
   *
   * @param rpOperations verifies assertions; the same instance Spring's WebAuthn filters use
   * @param credentialRepository maps WebAuthn credential ids to who's credential ids
   * @param whoService resolves credentials to principals
   * @param eventPublisher where {@link SignInFailed} is published
   */
  public WhoPasskeyAuthenticationProvider(
      WebAuthnRelyingPartyOperations rpOperations,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      WhoService whoService,
      WhoEventPublisher eventPublisher) {
    this.rpOperations = requireNonNull(rpOperations, "rpOperations must not be null");
    this.credentialRepository =
        requireNonNull(credentialRepository, "credentialRepository must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    RelyingPartyAuthenticationRequest request =
        ((WebAuthnAuthenticationRequestToken) authentication).getWebAuthnRequest();
    Optional<UUID> credentialUuid;
    try {
      // Verifies the signature, user verification and origin, and updates the signature count.
      rpOperations.authenticate(request);
      // The raw id is the credential the relying party just looked up and verified.
      credentialUuid = credentialRepository.credentialUuid(request.getPublicKey().getRawId());
    } catch (RuntimeException _) {
      // no cause: Spring's and webauthn4j's messages can name the credential id, and a cause
      // reaches authentication-failure events and logs
      throw failed(SignInFailureReason.INVALID_ASSERTION, null, null);
    }
    if (credentialUuid.isEmpty()) {
      throw failed(SignInFailureReason.INVALID_ASSERTION, null, null);
    }
    UUID uuid = credentialUuid.get();
    Resolution resolution;
    try {
      resolution = whoService.resolveDetailed(() -> uuid);
    } catch (RuntimeException _) {
      // a fault in who, not a refused sign-in: no reason to report, and the answer stays uniform
      throw new BadCredentialsException(INVALID_CREDENTIAL);
    }
    return switch (resolution) {
      case Resolved(WhoPrincipal principal) ->
          new WhoAuthenticationToken(principal, uuid, SignInMethod.PASSKEY);
      case Inactive(UUID identityId) ->
          throw failed(SignInFailureReason.INACTIVE_IDENTITY, identityId, uuid);
      // revoked after the relying party read it, or its identity is gone: an unknown credential
      case NoIdentity(UUID identityId) ->
          throw failed(SignInFailureReason.INVALID_ASSERTION, identityId, uuid);
      case NotLinked() -> throw failed(SignInFailureReason.INVALID_ASSERTION, null, uuid);
    };
  }

  /** Logs and publishes a failure, and returns the uniform exception to throw. */
  private BadCredentialsException failed(
      SignInFailureReason reason, UUID identityId, UUID credentialId) {
    log.debug(
        "Passkey sign-in failed: method={}, reason={}, identityId={}, credentialId={}",
        SignInMethod.PASSKEY,
        reason,
        identityId,
        credentialId);
    eventPublisher.publish(
        new SignInFailed(
            clock.instant(), SignInMethod.PASSKEY, reason, null, identityId, credentialId));
    return new BadCredentialsException(INVALID_CREDENTIAL);
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return WebAuthnAuthenticationRequestToken.class.isAssignableFrom(authentication);
  }
}
