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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Repository for persisting and retrieving {@link EnrollmentToken} instances. */
public interface EnrollmentTokenRepository {

  /**
   * Saves (inserts or updates) the given token.
   *
   * @param token the token to save
   * @return the saved token (same instance)
   */
  EnrollmentToken save(EnrollmentToken token);

  /**
   * Finds a token by its primary key.
   *
   * @param id the token's UUID
   * @return the token, or empty if not found
   */
  Optional<EnrollmentToken> findById(UUID id);

  /**
   * Finds a token by its shareable value.
   *
   * @param value the token value shared with the user
   * @return the token, or empty if not found
   */
  Optional<EnrollmentToken> findByValue(String value);

  /**
   * Revokes all {@code PENDING} tokens for the given identity. No-op if none exist.
   *
   * @param identityId the identity whose pending tokens should be revoked
   */
  void revokeAllPendingForIdentity(UUID identityId);

  /**
   * Atomically marks a token {@code REDEEMED} if, and only if, it is still {@code PENDING} and has
   * not expired at {@code now}.
   *
   * @param id the token's UUID
   * @param now the instant to test expiry and record as the redemption time
   * @return {@code true} if this call redeemed the token
   */
  boolean redeem(UUID id, Instant now);

  /**
   * Atomically marks a token {@code REVOKED} if, and only if, it is still {@code PENDING}.
   *
   * @param id the token's UUID
   * @return {@code true} if this call revoked the token
   */
  boolean revoke(UUID id);

  /**
   * Deletes the token with the given id. No-op if not found.
   *
   * @param id the token's UUID
   */
  void deleteById(UUID id);
}
