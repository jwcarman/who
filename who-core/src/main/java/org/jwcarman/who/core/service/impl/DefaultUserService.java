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

import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.repository.RolePermissionRepository;
import org.jwcarman.who.core.repository.RoleRepository;
import org.jwcarman.who.core.repository.UserRepository;
import org.jwcarman.who.core.repository.UserRoleRepository;
import org.jwcarman.who.core.service.UserService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Default implementation of {@link UserService} with business logic.
 * <p>
 * This implementation coordinates user lifecycle operations across multiple repositories
 * and enforces business rules such as validation of user and role existence.
 */
public class DefaultUserService implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    /**
     * Constructs a new DefaultUserService with required repositories.
     *
     * @param userRepository repository for user persistence
     * @param roleRepository repository for role persistence
     * @param userRoleRepository repository for user-role assignments
     * @param rolePermissionRepository repository for role-permission assignments
     */
    public DefaultUserService(UserRepository userRepository,
                           RoleRepository roleRepository,
                           UserRoleRepository userRoleRepository,
                           RolePermissionRepository rolePermissionRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Override
    public UUID createUser(UserStatus status) {
        User user = User.create(UUID.randomUUID(), status);
        user = userRepository.save(user);
        return user.id();
    }

    @Override
    public void activateUser(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User does not exist: " + userId));

        userRepository.save(user.withStatus(UserStatus.ACTIVE));
    }

    @Override
    public void deactivateUser(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User does not exist: " + userId));

        userRepository.save(user.withStatus(UserStatus.DISABLED));
    }

    @Override
    public void deleteUser(UUID userId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Remove all role assignments
        userRoleRepository.removeAllAssignmentsForUser(userId);

        // Delete user
        userRepository.deleteById(userId);
    }

    @Override
    public void assignRoleToUser(UUID userId, UUID roleId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Assign role to user
        userRoleRepository.assignRole(userId, roleId);
    }

    @Override
    public void removeRoleFromUser(UUID userId, UUID roleId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Check if role is assigned to user
        List<UUID> userRoles = userRoleRepository.findRoleIdsByUserId(userId);
        if (!userRoles.contains(roleId)) {
            throw new IllegalArgumentException(
                "Role is not assigned to user: " + roleId
            );
        }

        // Remove role from user
        userRoleRepository.removeRole(userId, roleId);
    }

    @Override
    public Set<String> resolvePermissions(UUID userId) {
        List<UUID> roleIds = userRoleRepository.findRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<String> permissions = rolePermissionRepository.findPermissionIdsByRoleIds(roleIds);
        return new HashSet<>(permissions); // Deduplicate
    }
}
