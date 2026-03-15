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

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing {@link Role} persistence.
 */
public interface RoleRepository {

    /**
     * Finds a role by its unique identifier.
     *
     * @param id the role UUID
     * @return an Optional containing the role, or empty if not found
     */
    Optional<Role> findById(UUID id);

    /**
     * Finds a role by its name.
     *
     * @param name the role name
     * @return an Optional containing the role, or empty if not found
     */
    Optional<Role> findByName(String name);

    /**
     * Saves (upserts) a role. Inserts if the role does not exist; updates the name if it does.
     *
     * @param role the role to save
     * @return the saved role
     */
    Role save(Role role);

    /**
     * Returns true if a role with the given id exists.
     *
     * @param id the role UUID
     * @return true if found, false otherwise
     */
    boolean existsById(UUID id);

    /**
     * Deletes the role with the given id. Cascade deletes associated permissions and identity assignments.
     *
     * @param id the role UUID
     */
    void deleteById(UUID id);
}
