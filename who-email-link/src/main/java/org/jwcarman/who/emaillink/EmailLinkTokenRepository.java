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
package org.jwcarman.who.emaillink;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage for sign-in link tokens. Each credential holds at most one live token. */
public interface EmailLinkTokenRepository {

  /**
   * Issues a token for the credential with one atomic upsert, which is also the resend limit: an
   * existing token younger than {@code resendInterval} is left untouched.
   *
   * @param credentialId the email credential
   * @param tokenHash the SHA-256 hash of the token value
   * @param codeHash the HMAC hash of the sign-in code
   * @param now the current time
   * @param expiresAt when the token expires
   * @param resendInterval the minimum age of an existing token before it may be replaced
   * @return {@code true} if a row changed, meaning the link should be sent
   */
  boolean issue(
      UUID credentialId,
      String tokenHash,
      String codeHash,
      Instant now,
      Instant expiresAt,
      Duration resendInterval);

  /**
   * Atomically counts one code attempt against the live token of the credential with this address.
   * Nothing is counted, and the result is empty, once the cap is reached or the token has expired.
   *
   * @param normalizedAddress the normalized email address
   * @param maxAttempts the most attempts a token allows
   * @param now the current time
   * @return the credential id and stored code hash, or empty if no attempt is available
   */
  Optional<EmailCodeAttempt> attemptCode(String normalizedAddress, int maxAttempts, Instant now);

  /**
   * Atomically consumes an unexpired token by its code hash.
   *
   * @param credentialId the email credential
   * @param codeHash the HMAC hash of the submitted code
   * @param now the current time
   * @return the credential id, or empty if the code does not match, was used, or expired
   */
  Optional<UUID> consumeCode(UUID credentialId, String codeHash, Instant now);

  /**
   * Atomically consumes an unexpired token.
   *
   * @param tokenHash the SHA-256 hash of the token value
   * @param now the current time
   * @return the credential id, or empty if the token is unknown, used, or expired
   */
  Optional<UUID> consume(String tokenHash, Instant now);

  /**
   * Deletes the credential's token only if it is still the one with this hash, so a failed send
   * never removes a newer link.
   *
   * @param credentialId the email credential
   * @param tokenHash the SHA-256 hash of the token to delete
   */
  void delete(UUID credentialId, String tokenHash);
}
