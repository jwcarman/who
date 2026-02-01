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
package org.jwcarman.who.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.PreferencesService;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferencesControllerTest {

    @Mock
    private PreferencesService preferencesService;

    @InjectMocks
    private PreferencesController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldGetPreferences() {
        // Given
        UUID userId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(userId, Set.of());
        String namespace = "ui";

        JsonNode preferences = objectMapper.readTree("{\"theme\":\"dark\",\"fontSize\":14}");
        when(preferencesService.getPreferences(userId, namespace, JsonNode.class))
                .thenReturn(preferences);

        // When
        JsonNode result = controller.getPreferences(principal, namespace);

        // Then
        assertThat(result).isEqualTo(preferences);
        verify(preferencesService).getPreferences(userId, namespace, JsonNode.class);
    }

    @Test
    void shouldSetPreferences() {
        // Given
        UUID userId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(userId, Set.of());
        String namespace = "ui";
        JsonNode preferences = objectMapper.readTree("{\"theme\":\"light\",\"fontSize\":16}");

        // When
        controller.setPreferences(principal, namespace, preferences);

        // Then
        verify(preferencesService).setPreferences(userId, namespace, preferences);
    }
}
