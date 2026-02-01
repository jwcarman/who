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
package org.jwcarman.who.jdbc.repository;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.jdbc.JdbcTestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcTestConfiguration.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
class JdbcUserRepositoryTest {

    @Autowired
    private JdbcUserRepository userRepository;

    @Test
    void shouldSaveAndFindUser() {
        // Given
        User user = User.create(UUID.randomUUID(), UserStatus.ACTIVE);

        // When
        User saved = userRepository.save(user);
        Optional<User> found = userRepository.findById(user.id());

        // Then
        assertThat(saved).isEqualTo(user);
        assertThat(found).isPresent().contains(user);
    }

    @Test
    void shouldUpdateUser() {
        // Given
        User user = User.create(UUID.randomUUID(), UserStatus.ACTIVE);
        userRepository.save(user);

        // When
        User updated = user.withStatus(UserStatus.DISABLED);
        userRepository.save(updated);
        Optional<User> found = userRepository.findById(user.id());

        // Then
        assertThat(found).hasValueSatisfying(foundUser -> {
            assertThat(foundUser.status()).isEqualTo(UserStatus.DISABLED);
            assertThat(foundUser.updatedAt()).isAfter(foundUser.createdAt());
        });
    }

    @Test
    void shouldReturnEmptyWhenUserNotFound() {
        // When
        Optional<User> found = userRepository.findById(UUID.randomUUID());

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldCheckIfUserExists() {
        // Given
        User user = User.create(UUID.randomUUID(), UserStatus.ACTIVE);
        userRepository.save(user);

        // When/Then
        assertThat(userRepository.existsById(user.id())).isTrue();
        assertThat(userRepository.existsById(UUID.randomUUID())).isFalse();
    }

    @Test
    void shouldDeleteUser() {
        // Given
        User user = User.create(UUID.randomUUID(), UserStatus.ACTIVE);
        userRepository.save(user);

        // When
        userRepository.deleteById(user.id());

        // Then
        assertThat(userRepository.findById(user.id())).isEmpty();
    }
}
