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
package org.jwcarman.who.enrollment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing enrollment tokens that link credentials to identities.
 *
 * <p>The application is responsible for delivering the token value to the user (e.g. via email) and
 * for creating the {@link Credential} before calling {@link #enroll(String, Credential)}. This
 * service only handles the token lifecycle and the credential-to-identity link.
 */
public class WhoEnrollmentService {

  private final EnrollmentTokenRepository enrollmentTokenRepository;
  private final IdentityRepository identityRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final Duration tokenExpiration;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a service that publishes no events.
   *
   * @param enrollmentTokenRepository where tokens are stored
   * @param identityRepository where identities are stored
   * @param credentialIdentityRepository where credential-to-identity links are stored
   * @param tokenExpiration how long a new token stays valid
   */
  public WhoEnrollmentService(
      EnrollmentTokenRepository enrollmentTokenRepository,
      IdentityRepository identityRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      Duration tokenExpiration) {
    this(
        enrollmentTokenRepository,
        identityRepository,
        credentialIdentityRepository,
        tokenExpiration,
        WhoEventPublisher.NOOP,
        Clock.systemUTC());
  }

  /**
   * Creates a service that publishes an event for each token and credential change.
   *
   * @param enrollmentTokenRepository where tokens are stored
   * @param identityRepository where identities are stored
   * @param credentialIdentityRepository where credential-to-identity links are stored
   * @param tokenExpiration how long a new token stays valid
   * @param eventPublisher where the enrollment events are published
   * @param clock the clock that decides token expiry on redemption and stamps events
   */
  public WhoEnrollmentService(
      EnrollmentTokenRepository enrollmentTokenRepository,
      IdentityRepository identityRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      Duration tokenExpiration,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.enrollmentTokenRepository = enrollmentTokenRepository;
    this.identityRepository = identityRepository;
    this.credentialIdentityRepository = credentialIdentityRepository;
    this.tokenExpiration = tokenExpiration;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /**
   * Creates and persists a new enrollment token for the given identity.
   *
   * <p>The caller is responsible for delivering {@code token.value()} to the user.
   *
   * @param identity the identity to enroll a credential for
   * @return the newly created {@code PENDING} token
   * @throws IllegalArgumentException if no identity with the given id exists
   */
  public EnrollmentToken createToken(Identity identity) {
    if (!identityRepository.existsById(identity.id())) {
      throw new IllegalArgumentException("Identity not found: " + identity.id());
    }
    for (UUID revokedId : enrollmentTokenRepository.revokePendingForIdentity(identity.id())) {
      eventPublisher.publish(new EnrollmentTokenRevoked(clock.instant(), identity.id(), revokedId));
    }
    EnrollmentToken rawToken = EnrollmentToken.create(identity, tokenExpiration, clock.instant());
    EnrollmentToken hashedToken =
        new EnrollmentToken(
            rawToken.id(),
            rawToken.identityId(),
            MessageDigests.sha256Hex(rawToken.value()),
            rawToken.status(),
            rawToken.createdAt(),
            rawToken.expiresAt(),
            null);
    enrollmentTokenRepository.save(hashedToken);
    eventPublisher.publish(
        new EnrollmentTokenCreated(clock.instant(), identity.id(), rawToken.id()));
    return rawToken;
  }

  /**
   * Redeems an enrollment token, linking the credential to the identity.
   *
   * <p>The token must be {@code PENDING} and not expired, and its identity {@code ACTIVE}. The
   * token is redeemed with a single conditional update, so concurrent attempts cannot both succeed;
   * the credential is then linked to the identity in the same transaction.
   *
   * @param tokenValue the shareable token value given to the user
   * @param credential the credential to link
   * @return the identity the credential was linked to
   * @throws EnrollmentTokenNotFoundException if no token with the given value exists
   * @throws EnrollmentTokenExpiredException if the token has passed its expiration time
   * @throws EnrollmentTokenNotPendingException if the token is already redeemed or revoked
   * @throws EnrollmentIdentityInactiveException if the token's identity is not active
   * @throws CredentialAlreadyLinkedException if the credential is already linked to an identity
   */
  @Transactional
  public Identity enroll(String tokenValue, Credential credential) {
    EnrollmentToken token =
        enrollmentTokenRepository
            .findByValue(MessageDigests.sha256Hex(tokenValue))
            .orElseThrow(EnrollmentTokenNotFoundException::new);

    Identity identity =
        identityRepository
            .findById(token.identityId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Identity disappeared during enrollment: " + token.identityId()));
    if (identity.status() != IdentityStatus.ACTIVE) {
      throw new EnrollmentIdentityInactiveException(identity.id());
    }

    Instant now = clock.instant();
    if (!enrollmentTokenRepository.redeem(token.id(), now)) {
      EnrollmentToken current =
          enrollmentTokenRepository
              .findById(token.id())
              .orElseThrow(EnrollmentTokenNotFoundException::new);
      if (!now.isBefore(current.expiresAt())) {
        throw new EnrollmentTokenExpiredException(current.id());
      }
      throw new EnrollmentTokenNotPendingException(current.id(), current.status());
    }

    try {
      credentialIdentityRepository.link(credential.id(), identity.id());
    } catch (DuplicateKeyException _) {
      throw new CredentialAlreadyLinkedException(credential.id());
    }
    eventPublisher.publish(
        new CredentialAdded(
            clock.instant(),
            identity.id(),
            credential.id(),
            credential.kind(),
            credential.label().orElse(null)));
    return identity;
  }

  /**
   * Revokes an enrollment token, preventing it from being redeemed.
   *
   * @param token the token to revoke
   * @throws EnrollmentTokenNotFoundException if the token does not exist
   */
  @Transactional
  public void revokeToken(EnrollmentToken token) {
    EnrollmentToken current =
        enrollmentTokenRepository
            .findById(token.id())
            .orElseThrow(EnrollmentTokenNotFoundException::new);
    if (enrollmentTokenRepository.revoke(current.id())) {
      eventPublisher.publish(
          new EnrollmentTokenRevoked(clock.instant(), current.identityId(), current.id()));
    }
  }

  /**
   * Looks up a token by its shareable value without side effects.
   *
   * @param tokenValue the token value
   * @return the token, or empty if not found
   */
  public Optional<EnrollmentToken> findToken(String tokenValue) {
    return enrollmentTokenRepository.findByValue(MessageDigests.sha256Hex(tokenValue));
  }
}
