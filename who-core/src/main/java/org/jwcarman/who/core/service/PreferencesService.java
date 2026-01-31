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
    @SuppressWarnings("unchecked")
    <T> T mergePreferences(Class<T> type, T... layers);
}
