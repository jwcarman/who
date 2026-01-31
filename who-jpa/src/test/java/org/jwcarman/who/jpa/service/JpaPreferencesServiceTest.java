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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.jpa.entity.UserPreferencesEntity;
import org.jwcarman.who.jpa.repository.UserPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaPreferencesServiceTest {

    @Mock
    private UserPreferencesRepository repository;

    private JpaPreferencesService service;

    @BeforeEach
    void setUp() {
        service = new JpaPreferencesService(repository, new ObjectMapper());
    }

    static class TestPrefs {
        public String theme;
        public int fontSize;
        public boolean notifications;

        public TestPrefs() {}

        public TestPrefs(String theme, int fontSize, boolean notifications) {
            this.theme = theme;
            this.fontSize = fontSize;
            this.notifications = notifications;
        }
    }

    @Test
    void getPreferences_whenNotStored_returnsDefaults() throws Exception {
        // Given
        UUID userId = UUID.randomUUID();
        String namespace = "ui";
        when(repository.findByUserIdAndNamespace(userId, namespace))
            .thenReturn(Optional.empty());

        // When
        TestPrefs prefs = service.getPreferences(userId, namespace, TestPrefs.class);

        // Then
        assertThat(prefs).isNotNull();
        assertThat(prefs.theme).isNull();
        assertThat(prefs.fontSize).isZero();
        assertThat(prefs.notifications).isFalse();
    }

    @Test
    void getPreferences_whenStored_returnsStoredPreferences() throws Exception {
        // Given
        UUID userId = UUID.randomUUID();
        String namespace = "ui";
        UserPreferencesEntity entity = new UserPreferencesEntity();
        entity.setUserId(userId);
        entity.setNamespace(namespace);
        entity.setPrefsJson("{\"theme\":\"dark\",\"fontSize\":14,\"notifications\":true}");

        when(repository.findByUserIdAndNamespace(userId, namespace))
            .thenReturn(Optional.of(entity));

        // When
        TestPrefs prefs = service.getPreferences(userId, namespace, TestPrefs.class);

        // Then
        assertThat(prefs.theme).isEqualTo("dark");
        assertThat(prefs.fontSize).isEqualTo(14);
        assertThat(prefs.notifications).isTrue();
    }

    @Test
    void setPreferences_savesJsonToRepository() throws Exception {
        // Given
        UUID userId = UUID.randomUUID();
        String namespace = "ui";
        TestPrefs prefs = new TestPrefs("light", 16, false);

        // When
        service.setPreferences(userId, namespace, prefs);

        // Then
        verify(repository).save(any(UserPreferencesEntity.class));
    }

    @Test
    void mergePreferences_mergesMultipleLayers() throws Exception {
        // Given
        TestPrefs defaults = new TestPrefs("light", 12, false);
        TestPrefs orgPrefs = new TestPrefs("dark", 0, false);
        TestPrefs userPrefs = new TestPrefs(null, 16, true);

        // When
        TestPrefs merged = service.mergePreferences(TestPrefs.class, defaults, orgPrefs, userPrefs);

        // Then
        assertThat(merged.theme).isEqualTo("dark");
        assertThat(merged.fontSize).isEqualTo(16);
        assertThat(merged.notifications).isTrue();
    }
}
