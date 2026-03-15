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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Management service for administering roles, permissions, and identity-role assignments.
 * All multi-step operations are transactional.
 */
@Service
public class RbacService {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final IdentityRoleRepository identityRoleRepository;

    public RbacService(RoleRepository roleRepository,
                RolePermissionRepository rolePermissionRepository,
                IdentityRoleRepository identityRoleRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.identityRoleRepository = identityRoleRepository;
    }

    /**
     * Creates a new role with the given name.
     *
     * @param name the role name (must be unique)
     * @return the UUID of the newly created role
     * @throws IllegalArgumentException if a role with that name already exists
     */
    @Transactional
    public UUID createRole(String name) {
        if (roleRepository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("Role already exists with name: " + name);
        }
        Role role = Role.create(UUID.randomUUID(), name);
        roleRepository.save(role);
        return role.id();
    }

    /**
     * Deletes the role with the given id and cascades to all associated permissions and assignments.
     *
     * @param roleId the role UUID
     * @throws IllegalArgumentException if no role with that id exists
     */
    @Transactional
    public void deleteRole(UUID roleId) {
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }
        roleRepository.deleteById(roleId);
    }

    /**
     * Grants a permission string to the given role.
     *
     * @param roleId     the role UUID
     * @param permission the permission string to grant
     * @throws IllegalArgumentException if the role does not exist
     */
    @Transactional
    public void addPermissionToRole(UUID roleId, String permission) {
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }
        rolePermissionRepository.addPermission(roleId, permission);
    }

    /**
     * Revokes a permission string from the given role.
     *
     * @param roleId     the role UUID
     * @param permission the permission string to revoke
     * @throws IllegalArgumentException if the permission is not currently assigned to the role
     */
    @Transactional
    public void removePermissionFromRole(UUID roleId, String permission) {
        Set<String> current = rolePermissionRepository.findPermissionsByRoleId(roleId);
        if (!current.contains(permission)) {
            throw new IllegalArgumentException(
                    "Permission '" + permission + "' is not assigned to role: " + roleId);
        }
        rolePermissionRepository.removePermission(roleId, permission);
    }

    /**
     * Assigns a role to an identity.
     *
     * @param identityId the identity UUID
     * @param roleId     the role UUID
     * @throws IllegalArgumentException if the role does not exist
     */
    @Transactional
    public void assignRoleToIdentity(UUID identityId, UUID roleId) {
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }
        identityRoleRepository.assignRole(identityId, roleId);
    }

    /**
     * Removes a role assignment from an identity.
     *
     * @param identityId the identity UUID
     * @param roleId     the role UUID
     * @throws IllegalArgumentException if the role is not currently assigned to the identity
     */
    @Transactional
    public void removeRoleFromIdentity(UUID identityId, UUID roleId) {
        if (!identityRoleRepository.findRoleIdsByIdentityId(identityId).contains(roleId)) {
            throw new IllegalArgumentException(
                    "Role " + roleId + " is not assigned to identity: " + identityId);
        }
        identityRoleRepository.removeRole(identityId, roleId);
    }
}
