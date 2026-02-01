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
package org.jwcarman.who.core.service;

import java.util.UUID;

/**
 * Service for managing the RBAC model (roles and permissions).
 */
public interface RbacService {

    /**
     * Create a new role.
     *
     * @param roleName the name of the role
     * @return the ID of the newly created role
     * @throws IllegalArgumentException if a role with the same name already exists
     */
    UUID createRole(String roleName);

    /**
     * Delete a role.
     *
     * @param roleId the ID of the role to delete
     * @throws IllegalArgumentException if the role does not exist
     */
    void deleteRole(UUID roleId);

    /**
     * Add a permission to a role.
     *
     * @param roleId the role ID
     * @param permission the permission string to add
     * @throws IllegalArgumentException if the role does not exist
     * @throws IllegalArgumentException if the permission does not exist
     */
    void addPermissionToRole(UUID roleId, String permission);

    /**
     * Remove a permission from a role.
     *
     * @param roleId the role ID
     * @param permission the permission string to remove
     * @throws IllegalArgumentException if the role does not exist
     * @throws IllegalArgumentException if the permission is not assigned to the role
     */
    void removePermissionFromRole(UUID roleId, String permission);
}
