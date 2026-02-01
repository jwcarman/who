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
package org.jwcarman.who.core.repository;

import org.jwcarman.who.core.domain.Role;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for persisting and retrieving roles.
 */
public interface RoleRepository {

    /**
     * Find a role by ID.
     *
     * @param id the role ID
     * @return the role, or empty if not found
     */
    Optional<Role> findById(UUID id);

    /**
     * Find a role by name.
     *
     * @param name the role name
     * @return the role, or empty if not found
     */
    Optional<Role> findByName(String name);

    /**
     * Save a role (create or update).
     *
     * @param role the role to save
     * @return the saved role
     */
    Role save(Role role);

    /**
     * Check if a role exists.
     *
     * @param id the role ID
     * @return true if the role exists
     */
    boolean existsById(UUID id);

    /**
     * Delete a role.
     *
     * @param id the role ID
     */
    void deleteById(UUID id);
}
