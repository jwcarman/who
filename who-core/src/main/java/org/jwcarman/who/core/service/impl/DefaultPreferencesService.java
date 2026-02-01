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

import org.jwcarman.who.core.domain.UserPreferences;
import org.jwcarman.who.core.exception.WhoException;
import org.jwcarman.who.core.repository.UserPreferencesRepository;
import org.jwcarman.who.core.service.PreferencesService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Core implementation of PreferencesService.
 */
public class DefaultPreferencesService implements PreferencesService {

    private final UserPreferencesRepository repository;
    private final ObjectMapper objectMapper;
    private final JsonPreferencesMerger merger;

    public DefaultPreferencesService(UserPreferencesRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.merger = new JsonPreferencesMerger();
    }

    @Override
    public <T> T getPreferences(UUID userId, String namespace, Class<T> type) {
        Optional<UserPreferences> preferences = repository.findByUserIdAndNamespace(userId, namespace);

        if (preferences.isEmpty()) {
            // Return defaults (empty instance)
            try {
                return objectMapper.readValue("{}", type);
            } catch (JacksonException e) {
                throw new WhoException("Failed to deserialize default preferences", e);
            }
        }

        try {
            return objectMapper.readValue(preferences.get().data(), type);
        } catch (JacksonException e) {
            throw new WhoException("Failed to deserialize preferences", e);
        }
    }

    @Override
    public <T> void setPreferences(UUID userId, String namespace, T preferences) {
        try {
            String json = objectMapper.writeValueAsString(preferences);

            UserPreferences existingOrNew = repository.findByUserIdAndNamespace(userId, namespace)
                .map(existing -> existing.withData(json))
                .orElseGet(() -> UserPreferences.create(UUID.randomUUID(), userId, namespace, json));

            repository.save(existingOrNew);
        } catch (JacksonException e) {
            throw new WhoException("Failed to serialize preferences", e);
        }
    }

    @Override
    @SafeVarargs
    public final <T> T mergePreferences(Class<T> type, T... layers) {
        if (layers.length == 0) {
            return null;
        }

        try {
            JsonNode[] jsonLayers = new JsonNode[layers.length];
            for (int i = 0; i < layers.length; i++) {
                jsonLayers[i] = objectMapper.valueToTree(layers[i]);
            }

            JsonNode merged = merger.merge(jsonLayers);
            return objectMapper.treeToValue(merged, type);
        } catch (JacksonException e) {
            throw new WhoException("Failed to merge preferences", e);
        }
    }
}
