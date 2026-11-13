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
package org.jwcarman.who.apikey;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.spi.Credential;

/**
 * Credential representing an API key, stored as a SHA-256 hash.
 *
 * <p>The raw key is never stored — only the hash reaches the database. The {@link #id()} UUID is
 * stable for the lifetime of the credential and used as the FK to the identity mapping table. The
 * {@link #name()} is a human-readable label (e.g. "Production server", "CI pipeline") to help users
 * identify and manage their keys; it is also the label security events report.
 *
 * @param id the stable UUID for this credential record
 * @param name a human-readable label identifying this key (e.g. "Production server")
 * @param keyHash the SHA-256 hex digest of the raw API key
 */
public record ApiKeyCredential(UUID id, String name, String keyHash) implements Credential {

  /** Compact constructor — validates that no field is {@code null}. */
  public ApiKeyCredential {
    requireNonNull(id, "id must not be null");
    requireNonNull(name, "name must not be null");
    requireNonNull(keyHash, "keyHash must not be null");
  }

  /**
   * Returns {@link CredentialKind#API_KEY}.
   *
   * @return {@link CredentialKind#API_KEY}
   */
  @Override
  public CredentialKind kind() {
    return CredentialKind.API_KEY;
  }

  /**
   * Returns the key's {@link #name()}.
   *
   * @return the name
   */
  @Override
  public Optional<String> label() {
    return Optional.of(name);
  }
}
