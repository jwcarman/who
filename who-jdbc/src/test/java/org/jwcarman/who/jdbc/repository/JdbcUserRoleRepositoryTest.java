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

import org.jwcarman.who.core.domain.Role;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.jdbc.JdbcTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcTestConfiguration.class)

@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class JdbcUserRoleRepositoryTest {

    @Autowired
    private JdbcUserRoleRepository userRoleRepository;

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcRoleRepository roleRepository;

    private UUID userId;
    private UUID adminRoleId;
    private UUID userRoleId;

    @BeforeEach
    void setUp() {
        // Create test user
        User user = User.create(UUID.randomUUID(), UserStatus.ACTIVE);
        userRepository.save(user);
        userId = user.id();

        // Create test roles
        Role adminRole = Role.create(UUID.randomUUID(), "admin");
        Role userRole = Role.create(UUID.randomUUID(), "user");
        roleRepository.save(adminRole);
        roleRepository.save(userRole);
        adminRoleId = adminRole.id();
        userRoleId = userRole.id();
    }

    @Test
    void shouldAssignRoleToUser() {
        // When
        userRoleRepository.assignRole(userId, adminRoleId);

        // Then
        List<UUID> roles = userRoleRepository.findRoleIdsByUserId(userId);
        assertThat(roles).containsExactly(adminRoleId);
    }

    @Test
    void shouldAssignMultipleRolesToUser() {
        // When
        userRoleRepository.assignRole(userId, adminRoleId);
        userRoleRepository.assignRole(userId, userRoleId);

        // Then
        List<UUID> roles = userRoleRepository.findRoleIdsByUserId(userId);
        assertThat(roles).containsExactlyInAnyOrder(adminRoleId, userRoleId);
    }

    @Test
    void shouldNotDuplicateRoleAssignment() {
        // When
        userRoleRepository.assignRole(userId, adminRoleId);
        userRoleRepository.assignRole(userId, adminRoleId); // Duplicate

        // Then
        List<UUID> roles = userRoleRepository.findRoleIdsByUserId(userId);
        assertThat(roles).containsExactly(adminRoleId);
    }

    @Test
    void shouldRemoveRoleFromUser() {
        // Given
        userRoleRepository.assignRole(userId, adminRoleId);
        userRoleRepository.assignRole(userId, userRoleId);

        // When
        userRoleRepository.removeRole(userId, adminRoleId);

        // Then
        List<UUID> roles = userRoleRepository.findRoleIdsByUserId(userId);
        assertThat(roles).containsExactly(userRoleId);
    }

    @Test
    void shouldRemoveAllAssignmentsForRole() {
        // Given
        UUID user2Id = UUID.randomUUID();
        userRepository.save(User.create(user2Id, UserStatus.ACTIVE));

        userRoleRepository.assignRole(userId, adminRoleId);
        userRoleRepository.assignRole(user2Id, adminRoleId);

        // When
        userRoleRepository.removeAllAssignmentsForRole(adminRoleId);

        // Then
        assertThat(userRoleRepository.findRoleIdsByUserId(userId)).isEmpty();
        assertThat(userRoleRepository.findRoleIdsByUserId(user2Id)).isEmpty();
    }

    @Test
    void shouldRemoveAllAssignmentsForUser() {
        // Given
        userRoleRepository.assignRole(userId, adminRoleId);
        userRoleRepository.assignRole(userId, userRoleId);

        // When
        userRoleRepository.removeAllAssignmentsForUser(userId);

        // Then
        assertThat(userRoleRepository.findRoleIdsByUserId(userId)).isEmpty();
    }

    @Test
    void shouldReturnEmptyListWhenUserHasNoRoles() {
        // When
        List<UUID> roles = userRoleRepository.findRoleIdsByUserId(userId);

        // Then
        assertThat(roles).isEmpty();
    }
}
