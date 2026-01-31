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
 * Service for managing users, roles, and identities.
 */
public interface WhoManagementService {

    // External identity management

    /**
     * Link an external identity to an internal user.
     *
     * @param userId the internal user ID
     * @param issuer the JWT issuer (iss claim)
     * @param subject the JWT subject (sub claim)
     * @throws IllegalArgumentException if the identity is already linked to another user
     * @throws IllegalArgumentException if the user does not exist
     */
    void linkExternalIdentity(UUID userId, String issuer, String subject);

    /**
     * Unlink an external identity from a user.
     *
     * @param userId the internal user ID
     * @param externalIdentityId the ID of the external identity to unlink
     * @throws IllegalArgumentException if the user does not exist
     * @throws IllegalArgumentException if the external identity does not exist or is not linked to the user
     */
    void unlinkExternalIdentity(UUID userId, UUID externalIdentityId);

    // Role management

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
     * Assign a role to a user.
     *
     * @param userId the user ID
     * @param roleId the role ID
     * @throws IllegalArgumentException if the user does not exist
     * @throws IllegalArgumentException if the role does not exist
     */
    void assignRoleToUser(UUID userId, UUID roleId);

    /**
     * Remove a role from a user.
     *
     * @param userId the user ID
     * @param roleId the role ID
     * @throws IllegalArgumentException if the user does not exist
     * @throws IllegalArgumentException if the role does not exist or is not assigned to the user
     */
    void removeRoleFromUser(UUID userId, UUID roleId);

    // Permission management for roles

    /**
     * Add a permission to a role.
     *
     * @param roleId the role ID
     * @param permission the permission string to add
     * @throws IllegalArgumentException if the role does not exist
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
