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

import java.util.List;
import java.util.UUID;

/**
 * Repository for managing role-permission assignments.
 * This is a pure relationship repository - no domain model needed.
 */
public interface RolePermissionRepository {

    /**
     * Assign a permission to a role.
     *
     * @param roleId the role ID
     * @param permissionId the permission ID
     */
    void assignPermission(UUID roleId, String permissionId);

    /**
     * Remove a permission from a role.
     *
     * @param roleId the role ID
     * @param permissionId the permission ID
     */
    void removePermission(UUID roleId, String permissionId);

    /**
     * Remove all permission assignments for a role.
     *
     * @param roleId the role ID
     */
    void removeAllPermissionsForRole(UUID roleId);

    /**
     * Find all permission IDs assigned to a role.
     *
     * @param roleId the role ID
     * @return list of permission IDs
     */
    List<String> findPermissionIdsByRoleId(UUID roleId);

    /**
     * Find all permission IDs for multiple roles (deduplicated).
     * Optimized for entitlements resolution.
     *
     * @param roleIds the role IDs
     * @return list of permission IDs
     */
    List<String> findPermissionIdsByRoleIds(List<UUID> roleIds);
}

