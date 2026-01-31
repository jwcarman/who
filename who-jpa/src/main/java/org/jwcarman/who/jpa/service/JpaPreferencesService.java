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
package org.jwcarman.who.jpa.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.jwcarman.who.core.service.PreferencesService;
import org.jwcarman.who.core.service.impl.JsonPreferencesMerger;
import org.jwcarman.who.jpa.entity.UserPreferencesEntity;
import org.jwcarman.who.jpa.repository.UserPreferencesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class JpaPreferencesService implements PreferencesService {

    private final UserPreferencesRepository repository;
    private final ObjectMapper objectMapper;
    private final JsonPreferencesMerger merger;

    public JpaPreferencesService(UserPreferencesRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.merger = new JsonPreferencesMerger();
    }

    @Override
    public <T> T getPreferences(UUID userId, String namespace, Class<T> type) {
        Optional<UserPreferencesEntity> entity = repository.findByUserIdAndNamespace(userId, namespace);

        if (entity.isEmpty()) {
            // Return defaults (empty instance)
            try {
                return objectMapper.readValue("{}", type);
            } catch (JacksonException e) {
                throw new RuntimeException("Failed to deserialize default preferences", e);
            }
        }

        try {
            return objectMapper.readValue(entity.get().getPrefsJson(), type);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to deserialize preferences", e);
        }
    }

    @Override
    public <T> void setPreferences(UUID userId, String namespace, T preferences) {
        try {
            String json = objectMapper.writeValueAsString(preferences);

            UserPreferencesEntity entity = repository.findByUserIdAndNamespace(userId, namespace)
                    .orElseGet(() -> {
                        UserPreferencesEntity newEntity = new UserPreferencesEntity();
                        newEntity.setUserId(userId);
                        newEntity.setNamespace(namespace);
                        return newEntity;
                    });
            entity.setPrefsJson(json);

            repository.save(entity);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to serialize preferences", e);
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
            throw new RuntimeException("Failed to merge preferences", e);
        }
    }
}
