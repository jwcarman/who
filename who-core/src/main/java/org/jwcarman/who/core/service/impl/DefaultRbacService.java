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
package org.jwcarman.who.core.service.impl;

import org.jwcarman.who.core.domain.Role;
import org.jwcarman.who.core.repository.PermissionRepository;
import org.jwcarman.who.core.repository.RolePermissionRepository;
import org.jwcarman.who.core.repository.RoleRepository;
import org.jwcarman.who.core.repository.UserRoleRepository;
import org.jwcarman.who.core.service.RbacService;

import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link RbacService} with business logic.
 * <p>
 * This implementation coordinates role and permission management across multiple repositories,
 * enforcing business rules such as:
 * <ul>
 *   <li>Preventing duplicate role names</li>
 *   <li>Validating role and permission existence before assignments</li>
 *   <li>Cascading deletes when roles are removed</li>
 * </ul>
 */
public class DefaultRbacService implements RbacService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleRepository userRoleRepository;
    private final PermissionRepository permissionRepository;

    /**
     * Constructs a new DefaultRbacService with required repositories.
     *
     * @param roleRepository repository for role persistence
     * @param rolePermissionRepository repository for role-permission assignments
     * @param userRoleRepository repository for user-role assignments
     * @param permissionRepository repository for permission persistence
     */
    public DefaultRbacService(RoleRepository roleRepository,
                           RolePermissionRepository rolePermissionRepository,
                           UserRoleRepository userRoleRepository,
                           PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.permissionRepository = permissionRepository;
    }

    @Override
    public UUID createRole(String roleName) {
        // Check if role already exists
        if (roleRepository.findByName(roleName).isPresent()) {
            throw new IllegalArgumentException("Role already exists: " + roleName);
        }

        Role role = Role.create(UUID.randomUUID(), roleName);
        role = roleRepository.save(role);
        return role.id();
    }

    @Override
    public void deleteRole(UUID roleId) {
        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Delete role permissions
        rolePermissionRepository.removeAllPermissionsForRole(roleId);

        // Delete user-role assignments
        userRoleRepository.removeAllAssignmentsForRole(roleId);

        // Delete role
        roleRepository.deleteById(roleId);
    }

    @Override
    public void addPermissionToRole(UUID roleId, String permission) {
        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Validate permission exists
        if (!permissionRepository.existsById(permission)) {
            throw new IllegalArgumentException("Permission does not exist: " + permission);
        }

        // Assign permission to role
        rolePermissionRepository.assignPermission(roleId, permission);
    }

    @Override
    public void removePermissionFromRole(UUID roleId, String permission) {
        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Check if permission is assigned to role
        List<String> permissions = rolePermissionRepository.findPermissionIdsByRoleId(roleId);
        if (!permissions.contains(permission)) {
            throw new IllegalArgumentException(
                "Permission is not assigned to role: " + permission
            );
        }

        // Remove permission from role
        rolePermissionRepository.removePermission(roleId, permission);
    }
}
