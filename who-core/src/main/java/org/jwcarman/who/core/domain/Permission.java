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

import static java.util.Objects.requireNonNull;

/**
 * Immutable permission domain model.
 * The id is the permission string used in authorization checks (e.g., "task.read").
 * The description is a human-friendly explanation shown in admin UIs.
 */
public record Permission(
    String id,
    String description
) {
    public Permission {
        requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        // description can be null or empty (it's optional)
    }

    /**
     * Create a new permission with description.
     *
     * @param id the permission ID (e.g., "task.read")
     * @param description the human-friendly description
     * @return a new permission
     */
    public static Permission create(String id, String description) {
        return new Permission(id, description);
    }

    /**
     * Create a new permission without description.
     *
     * @param id the permission ID (e.g., "task.read")
     * @return a new permission
     */
    public static Permission create(String id) {
        return new Permission(id, null);
    }

    /**
     * Create a copy with updated description.
     *
     * @param newDescription the new description
     * @return a new permission instance with updated description
     */
    public Permission withDescription(String newDescription) {
        return new Permission(id, newDescription);
    }
}
