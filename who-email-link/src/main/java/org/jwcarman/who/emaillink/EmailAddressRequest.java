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

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A signed-in person's request to add an email address to their identity, or to make it theirs in
 * place of another, waiting for them to confirm the link or code sent to it.
 *
 * @param id the request; a new one gets a new id, which the code is bound to
 * @param identityId the identity that asked, the only one that can confirm it
 * @param address the address, normalized
 * @param replacesCredentialId the email credential the address replaces, or {@code null} to add it
 * @param createdAt when it was requested
 * @param expiresAt when the link and the code stop working
 */
public record EmailAddressRequest(
    UUID id,
    UUID identityId,
    String address,
    UUID replacesCredentialId,
    Instant createdAt,
    Instant expiresAt) {

  /** Validates that the required components are not null. */
  public EmailAddressRequest {
    requireNonNull(id, "id must not be null");
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(address, "address must not be null");
    requireNonNull(createdAt, "createdAt must not be null");
    requireNonNull(expiresAt, "expiresAt must not be null");
  }

  /**
   * Whether the address replaces one of the identity's addresses.
   *
   * @return {@code true} for a change
   */
  public boolean change() {
    return replacesCredentialId != null;
  }
}
