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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.id.Identifiers;

/**
 * An immutable enrollment token that links a {@link org.jwcarman.who.core.spi.Credential} to a
 * pre-existing {@link org.jwcarman.who.core.domain.Identity}.
 *
 * <p>The token value (a random UUID string) is what gets shared with the end user. Expiry is
 * determined at runtime — {@code EXPIRED} is not stored as a status.
 *
 * @param id stable UUID identifying this token record
 * @param identityId the identity this token is associated with
 * @param value the shareable token value delivered to the end user
 * @param status current lifecycle status ({@code PENDING}, {@code REDEEMED}, or {@code REVOKED})
 * @param createdAt when the token was created
 * @param expiresAt when the token expires
 * @param redeemedAt when the token was redeemed, or {@code null} if not yet redeemed
 */
public record EnrollmentToken(
    UUID id,
    UUID identityId,
    String value,
    EnrollmentTokenStatus status,
    Instant createdAt,
    Instant expiresAt,
    Instant redeemedAt) {

  /** Compact constructor for null-safety validation. */
  public EnrollmentToken {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(identityId, "identityId must not be null");
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  /**
   * Creates a new PENDING enrollment token for the given identity.
   *
   * @param identity the identity this token is for
   * @param expiration how long until the token expires
   * @return a new {@code PENDING} token
   */
  public static EnrollmentToken create(Identity identity, Duration expiration) {
    Instant now = Instant.now();
    return new EnrollmentToken(
        Identifiers.uuid(),
        identity.id(),
        Identifiers.uuid().toString(),
        EnrollmentTokenStatus.PENDING,
        now,
        now.plus(expiration),
        null);
  }

  /**
   * Returns {@code true} if the token has passed its expiration time.
   *
   * @return {@code true} if expired
   */
  public boolean isExpired() {
    return Instant.now().isAfter(expiresAt);
  }

  /**
   * Returns {@code true} if the token is {@code PENDING} and not yet expired.
   *
   * @return {@code true} if the token can be redeemed
   */
  public boolean isPending() {
    return status == EnrollmentTokenStatus.PENDING && !isExpired();
  }

  /**
   * Returns a new token with {@code REDEEMED} status.
   *
   * @return redeemed copy of this token
   */
  public EnrollmentToken redeem() {
    return new EnrollmentToken(
        id, identityId, value, EnrollmentTokenStatus.REDEEMED, createdAt, expiresAt, Instant.now());
  }

  /**
   * Returns a new token with {@code REVOKED} status.
   *
   * @return revoked copy of this token
   */
  public EnrollmentToken revoke() {
    return new EnrollmentToken(
        id, identityId, value, EnrollmentTokenStatus.REVOKED, createdAt, expiresAt, null);
  }
}
