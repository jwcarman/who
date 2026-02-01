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

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable user domain model.
 */
public record User(
    UUID id,
    UserStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public User {
        requireNonNull(id, "id must not be null");
        requireNonNull(status, "status must not be null");
        requireNonNull(createdAt, "createdAt must not be null");
        requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Create a new user with ACTIVE status.
     *
     * @param id the user ID
     * @return a new user
     */
    public static User create(UUID id) {
        return create(id, UserStatus.ACTIVE);
    }

    /**
     * Create a new user with specified status.
     *
     * @param id the user ID
     * @param status the initial status
     * @return a new user
     */
    public static User create(UUID id, UserStatus status) {
        Instant now = Instant.now();
        return new User(id, status, now, now);
    }

    /**
     * Create a copy with updated status.
     *
     * @param newStatus the new status
     * @return a new user instance with updated status and timestamp
     */
    public User withStatus(UserStatus newStatus) {
        return new User(id, newStatus, createdAt, Instant.now());
    }

    /**
     * Create a copy with updated timestamp (for touch operations).
     *
     * @return a new user instance with updated timestamp
     */
    public User touch() {
        return new User(id, status, createdAt, Instant.now());
    }
}
