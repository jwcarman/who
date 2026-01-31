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
 * Service for managing user preferences.
 */
public interface PreferencesService {

    /**
     * Get user preferences for a namespace.
     *
     * @param userId the user ID
     * @param namespace the preferences namespace
     * @param type the preference class type
     * @return deserialized preferences with defaults applied
     */
    <T> T getPreferences(UUID userId, String namespace, Class<T> type);

    /**
     * Set user preferences for a namespace (replace).
     *
     * @param userId the user ID
     * @param namespace the preferences namespace
     * @param preferences the preferences object
     */
    <T> void setPreferences(UUID userId, String namespace, T preferences);

    /**
     * Merge preference layers (later layers override earlier).
     *
     * @param type the preference class type
     * @param layers preference layers to merge
     * @return merged preferences
     */
    @SuppressWarnings("unchecked") // Varargs generic array creation is safe here
    <T> T mergePreferences(Class<T> type, T... layers);
}
