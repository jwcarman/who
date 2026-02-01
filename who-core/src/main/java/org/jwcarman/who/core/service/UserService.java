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

import org.jwcarman.who.core.domain.UserStatus;

import java.util.Set;
import java.util.UUID;

/**
 * Service for managing user lifecycle and role assignments.
 */
public interface UserService {

    /**
     * Create a new user.
     *
     * @param status the initial status of the user
     * @return the ID of the newly created user
     */
    UUID createUser(UserStatus status);

    /**
     * Activate a user.
     *
     * @param userId the user ID
     * @throws IllegalArgumentException if the user does not exist
     */
    void activateUser(UUID userId);

    /**
     * Deactivate a user.
     *
     * @param userId the user ID
     * @throws IllegalArgumentException if the user does not exist
     */
    void deactivateUser(UUID userId);

    /**
     * Delete a user.
     *
     * @param userId the user ID
     * @throws IllegalArgumentException if the user does not exist
     */
    void deleteUser(UUID userId);

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

    /**
     * Resolve effective permissions for a user.
     * Returns all permissions granted to the user through their assigned roles.
     *
     * @param userId the user ID
     * @return set of permission strings (e.g., "billing.invoice.read")
     */
    Set<String> resolvePermissions(UUID userId);
}
