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
package org.jwcarman.who.rbac;

import java.util.Objects;
import java.util.UUID;

/**
 * An immutable domain record representing a named role that can be assigned to identities
 * and granted permissions.
 */
public record Role(UUID id, String name) {

    /**
     * Compact constructor that validates all fields are non-null.
     */
    public Role {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Static factory method for creating a new Role.
     *
     * @param id   the unique identifier for the role
     * @param name the human-readable name of the role
     * @return a new Role instance
     */
    public static Role create(UUID id, String name) {
        return new Role(id, name);
    }
}
