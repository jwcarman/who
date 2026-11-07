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

import java.util.UUID;

import org.jwcarman.who.core.spi.Credential;

/**
 * A passkey, as a who credential. Its id is the passkey's {@code credential_uuid}, which links it
 * to an identity through {@link org.jwcarman.who.core.repository.CredentialIdentityRepository}.
 *
 * @param id the stable UUID for this passkey
 */
public record PasskeyCredential(UUID id) implements Credential {

  /** Compact constructor: validates that the id is not {@code null}. */
  public PasskeyCredential {
    requireNonNull(id, "id must not be null");
  }
}
