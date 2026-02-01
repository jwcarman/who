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

import org.jwcarman.who.core.domain.UserPreferences;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for user preferences.
 */
public interface UserPreferencesRepository {

    /**
     * Find user preferences by user ID and namespace.
     *
     * @param userId the user ID
     * @param namespace the preference namespace
     * @return the preferences, or empty if not found
     */
    Optional<UserPreferences> findByUserIdAndNamespace(UUID userId, String namespace);

    /**
     * Save user preferences (create or update).
     *
     * @param preferences the preferences to save
     * @return the saved preferences
     */
    UserPreferences save(UserPreferences preferences);

    /**
     * Delete user preferences.
     *
     * @param id the preferences ID
     */
    void deleteById(UUID id);
}
