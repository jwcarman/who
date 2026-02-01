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

import org.jwcarman.who.core.domain.User;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for persisting and retrieving users.
 */
public interface UserRepository {

    /**
     * Find a user by ID.
     *
     * @param id the user ID
     * @return the user, or empty if not found
     */
    Optional<User> findById(UUID id);

    /**
     * Save a user (create or update).
     *
     * @param user the user to save
     * @return the saved user
     */
    User save(User user);

    /**
     * Check if a user exists.
     *
     * @param id the user ID
     * @return true if the user exists
     */
    boolean existsById(UUID id);

    /**
     * Delete a user.
     *
     * @param id the user ID
     */
    void deleteById(UUID id);
}
