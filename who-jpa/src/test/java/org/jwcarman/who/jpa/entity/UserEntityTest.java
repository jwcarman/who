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
package org.jwcarman.who.jpa.entity;

import org.jwcarman.who.core.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserEntityTest {

    @Test
    void shouldGenerateIdOnCreate() {
        // Given
        UserEntity entity = new UserEntity();

        // When
        simulatePrePersist(entity);

        // Then
        assertThat(entity.getId()).isNotNull();
    }

    @Test
    void shouldPreserveExistingIdOnCreate() {
        // Given
        UUID existingId = UUID.randomUUID();
        UserEntity entity = new UserEntity();
        entity.setId(existingId);

        // When
        simulatePrePersist(entity);

        // Then
        assertThat(entity.getId()).isEqualTo(existingId);
    }

    @Test
    void shouldSetDefaultStatusToActive() {
        // Given
        UserEntity entity = new UserEntity();

        // When
        simulatePrePersist(entity);

        // Then
        assertThat(entity.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void shouldPreserveExistingStatus() {
        // Given
        UserEntity entity = new UserEntity();
        entity.setStatus(UserStatus.SUSPENDED);

        // When
        simulatePrePersist(entity);

        // Then
        assertThat(entity.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void shouldSetTimestampsOnCreate() {
        // Given
        UserEntity entity = new UserEntity();
        Instant before = Instant.now();

        // When
        simulatePrePersist(entity);

        // Then
        Instant after = Instant.now();
        assertThat(entity.getCreatedAt())
            .isNotNull()
            .isAfterOrEqualTo(before)
            .isBeforeOrEqualTo(after);
        assertThat(entity.getUpdatedAt())
            .isNotNull()
            .isAfterOrEqualTo(before)
            .isBeforeOrEqualTo(after);
    }

    @Test
    void shouldUpdateTimestampOnUpdate() throws InterruptedException {
        // Given
        UserEntity entity = new UserEntity();
        simulatePrePersist(entity);
        Instant originalUpdatedAt = entity.getUpdatedAt();

        // Small delay to ensure timestamp changes
        Thread.sleep(10);

        // When
        simulatePreUpdate(entity);

        // Then
        assertThat(entity.getUpdatedAt()).isAfter(originalUpdatedAt);
        assertThat(entity.getCreatedAt()).isNotNull(); // Should remain unchanged
    }

    // Helper methods to simulate JPA lifecycle callbacks
    private void simulatePrePersist(UserEntity entity) {
        try {
            var method = UserEntity.class.getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void simulatePreUpdate(UserEntity entity) {
        try {
            var method = UserEntity.class.getDeclaredMethod("onUpdate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
