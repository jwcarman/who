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

import org.jwcarman.who.core.spi.Credential;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Credential representing an API key, stored as a SHA-256 hash.
 *
 * <p>The raw key is never stored — only the hash reaches the database. The {@link #id()} UUID
 * is stable for the lifetime of the credential and used as the FK to the identity mapping table.
 * The {@link #name()} is a human-readable label (e.g. "Production server", "CI pipeline") to
 * help users identify and manage their keys.
 *
 * @param id      the stable UUID for this credential record
 * @param name    a human-readable label identifying this key (e.g. "Production server")
 * @param keyHash the SHA-256 hex digest of the raw API key
 */
public record ApiKeyCredential(UUID id, String name, String keyHash) implements Credential {

    /**
     * Compact constructor — validates that no field is {@code null}.
     */
    public ApiKeyCredential {
        requireNonNull(id, "id must not be null");
        requireNonNull(name, "name must not be null");
        requireNonNull(keyHash, "keyHash must not be null");
    }
}
