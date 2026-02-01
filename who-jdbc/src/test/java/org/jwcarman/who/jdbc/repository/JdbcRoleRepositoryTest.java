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
import org.jwcarman.who.core.domain.Role;
import org.jwcarman.who.jdbc.JdbcTestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcTestConfiguration.class)

@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class JdbcRoleRepositoryTest {

    @Autowired
    private JdbcRoleRepository roleRepository;

    @Test
    void shouldSaveAndFindRoleById() {
        // Given
        Role role = Role.create(UUID.randomUUID(), "admin");

        // When
        roleRepository.save(role);
        Optional<Role> found = roleRepository.findById(role.id());

        // Then
        assertThat(found).isPresent().contains(role);
    }

    @Test
    void shouldFindRoleByName() {
        // Given
        Role role = Role.create(UUID.randomUUID(), "admin");
        roleRepository.save(role);

        // When
        Optional<Role> found = roleRepository.findByName("admin");

        // Then
        assertThat(found).isPresent().contains(role);
    }

    @Test
    void shouldReturnEmptyWhenRoleNotFoundByName() {
        // When
        Optional<Role> found = roleRepository.findByName("nonexistent");

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldUpdateRole() {
        // Given
        Role role = Role.create(UUID.randomUUID(), "admin");
        roleRepository.save(role);

        // When
        Role updated = role.withName("super-admin");
        roleRepository.save(updated);
        Optional<Role> found = roleRepository.findById(role.id());

        // Then
        assertThat(found).hasValueSatisfying(foundRole ->
            assertThat(foundRole.name()).isEqualTo("super-admin"));
    }

    @Test
    void shouldCheckIfRoleExists() {
        // Given
        Role role = Role.create(UUID.randomUUID(), "admin");
        roleRepository.save(role);

        // When/Then
        assertThat(roleRepository.existsById(role.id())).isTrue();
        assertThat(roleRepository.existsById(UUID.randomUUID())).isFalse();
    }

    @Test
    void shouldDeleteRole() {
        // Given
        Role role = Role.create(UUID.randomUUID(), "admin");
        roleRepository.save(role);

        // When
        roleRepository.deleteById(role.id());

        // Then
        assertThat(roleRepository.findById(role.id())).isEmpty();
    }
}
