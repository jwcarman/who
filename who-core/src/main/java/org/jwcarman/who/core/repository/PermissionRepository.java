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

import org.jwcarman.who.core.domain.Permission;

import java.util.List;
import java.util.Optional;

/**
 * Repository for managing permissions.
 */
public interface PermissionRepository {

    /**
     * Find a permission by ID.
     *
     * @param id the permission ID
     * @return the permission, or empty if not found
     */
    Optional<Permission> findById(String id);

    /**
     * Find all permissions.
     *
     * @return list of all permissions
     */
    List<Permission> findAll();

    /**
     * Save a permission (create or update).
     *
     * @param permission the permission to save
     * @return the saved permission
     */
    Permission save(Permission permission);

    /**
     * Check if a permission exists.
     *
     * @param id the permission ID
     * @return true if the permission exists
     */
    boolean existsById(String id);

    /**
     * Delete a permission.
     *
     * @param id the permission ID
     */
    void deleteById(String id);
}
