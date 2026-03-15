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
package org.jwcarman.who.core.domain;

import org.jwcarman.who.core.Identifiers;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Stable internal identity record. All application domain objects FK against the {@code id}.
 */
public record Identity(
        UUID id,
        IdentityStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public Identity {
        requireNonNull(id, "id must not be null");
        requireNonNull(status, "status must not be null");
        requireNonNull(createdAt, "createdAt must not be null");
        requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Creates a new {@code Identity} with a generated UUID and both timestamps set to now.
     *
     * @param status the initial status
     * @return a new {@code Identity}
     */
    public static Identity create(IdentityStatus status) {
        Instant now = Instant.now();
        return new Identity(Identifiers.uuid(), status, now, now);
    }

    /**
     * Returns a new {@code Identity} with the given status and an updated {@code updatedAt} timestamp.
     *
     * @param newStatus the new status
     * @return updated {@code Identity}
     */
    public Identity withStatus(IdentityStatus newStatus) {
        return new Identity(id, newStatus, createdAt, Instant.now());
    }
}
