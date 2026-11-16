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

/** Stores the requests to add or change an email address, one per identity. */
public interface EmailAddressRequestRepository {

  /**
   * Saves the identity's request in place of an older one, unless one was made too recently: by
   * this identity for any address, or by anyone for this address, within {@code resendInterval}.
   *
   * @param request the request
   * @param tokenHash the SHA-256 hash of the link's token
   * @param codeHash the keyed hash of the code, bound to the request's id
   * @param resendInterval how long a request holds off another
   * @return whether it was saved
   */
  boolean save(
      EmailAddressRequest request, String tokenHash, String codeHash, Duration resendInterval);

  /**
   * Consumes the identity's live request with the token.
   *
   * @param identityId the identity confirming; a token works only for the identity that asked
   * @param tokenHash the SHA-256 hash of the presented token
   * @param now the current time
   * @return the request, or empty if none matches
   */
  Optional<EmailAddressRequest> consumeToken(UUID identityId, String tokenHash, Instant now);

  /**
   * Counts a code attempt against the identity's live request, if it has attempts left.
   *
   * @param identityId the identity confirming
   * @param maxAttempts how many attempts a code allows
   * @param now the current time
   * @return the request's id and code hash, or empty if there is no live request with attempts left
   */
  Optional<EmailAddressCodeAttempt> attemptCode(UUID identityId, int maxAttempts, Instant now);

  /**
   * Consumes a live request whose code hash matches.
   *
   * @param requestId the request
   * @param codeHash the presented code's hash
   * @param now the current time
   * @return the request, or empty if it was used, expired or the hash differs
   */
  Optional<EmailAddressRequest> consumeCode(UUID requestId, String codeHash, Instant now);

  /**
   * Deletes a request, as when its email could not be sent.
   *
   * @param requestId the request
   */
  void delete(UUID requestId);
}
