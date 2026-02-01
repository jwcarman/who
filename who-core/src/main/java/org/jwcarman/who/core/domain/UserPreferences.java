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

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable user preferences domain model.
 */
public record UserPreferences(
    UUID id,
    UUID userId,
    String namespace,
    String data
) {
    public UserPreferences {
        requireNonNull(id, "id must not be null");
        requireNonNull(userId, "userId must not be null");
        requireNonNull(namespace, "namespace must not be null");
        requireNonNull(data, "data must not be null");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
    }

    /**
     * Create new user preferences.
     *
     * @param id the preferences ID
     * @param userId the user ID
     * @param namespace the preference namespace
     * @param data the preferences data (JSON string)
     * @return a new user preferences instance
     */
    public static UserPreferences create(UUID id, UUID userId, String namespace, String data) {
        return new UserPreferences(id, userId, namespace, data);
    }

    /**
     * Create a copy with updated data.
     *
     * @param newData the new data
     * @return a new user preferences instance with updated data
     */
    public UserPreferences withData(String newData) {
        return new UserPreferences(id, userId, namespace, newData);
    }
}
