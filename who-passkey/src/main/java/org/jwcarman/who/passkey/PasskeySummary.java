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

import java.time.Instant;
import java.util.UUID;

/**
 * A passkey as shown to its owner.
 *
 * @param id the passkey's credential UUID, as {@link PasskeyService#revoke(UUID)} takes it
 * @param credentialId the passkey's WebAuthn credential id, base64url as Spring encodes it: the
 *     {@code {id}} of Spring's {@code DELETE /webauthn/register/{id}}
 * @param label the label given when the passkey was registered
 * @param created when the passkey was registered
 * @param lastUsed when the passkey was last used, or {@code null} if unknown
 */
public record PasskeySummary(
    UUID id, String credentialId, String label, Instant created, Instant lastUsed) {

  /** Compact constructor: validates the required fields. */
  public PasskeySummary {
    requireNonNull(id, "id must not be null");
    requireNonNull(credentialId, "credentialId must not be null");
    requireNonNull(label, "label must not be null");
    requireNonNull(created, "created must not be null");
  }
}
