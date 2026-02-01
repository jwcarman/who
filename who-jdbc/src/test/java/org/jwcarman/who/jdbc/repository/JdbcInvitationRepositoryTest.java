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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.jdbc.JdbcTestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcTestConfiguration.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
class JdbcInvitationRepositoryTest {

    @Autowired
    private JdbcInvitationRepository invitationRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID testRoleId;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        // Create a test role
        testRoleId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO who_role (id, name) VALUES (:id, :name)")
            .param("id", testRoleId)
            .param("name", "TEST_ROLE")
            .update();

        // Create a test user (admin)
        testUserId = UUID.randomUUID();
        User testUser = User.create(testUserId, UserStatus.ACTIVE);
        jdbcClient.sql("INSERT INTO who_user (id, status, created_at, updated_at) VALUES (:id, :status, :createdAt, :updatedAt)")
            .param("id", testUser.id())
            .param("status", testUser.status().name())
            .param("createdAt", Timestamp.from(testUser.createdAt()))
            .param("updatedAt", Timestamp.from(testUser.updatedAt()))
            .update();
    }

    @Test
    void shouldSaveAndFindInvitationById() {
        // Given
        Invitation invitation = Invitation.create(
            "test@example.com",
            testRoleId,
            testUserId,
            24
        );

        // When
        Invitation saved = invitationRepository.save(invitation);
        Optional<Invitation> found = invitationRepository.findById(invitation.id());

        // Then
        assertThat(saved).isEqualTo(invitation);
        assertThat(found).isPresent().contains(invitation);
    }

    @Test
    void shouldUpdateInvitation() {
        // Given
        Invitation invitation = Invitation.create(
            "test@example.com",
            testRoleId,
            testUserId,
            24
        );
        invitationRepository.save(invitation);

        // When
        Invitation accepted = invitation.accept();
        invitationRepository.save(accepted);
        Optional<Invitation> found = invitationRepository.findById(invitation.id());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(found.get().acceptedAt()).isNotNull();
    }

    @Test
    void shouldReturnEmptyWhenInvitationNotFoundById() {
        // When
        Optional<Invitation> found = invitationRepository.findById(UUID.randomUUID());

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindInvitationByToken() {
        // Given
        Invitation invitation = Invitation.create(
            "test@example.com",
            testRoleId,
            testUserId,
            24
        );
        invitationRepository.save(invitation);

        // When
        Optional<Invitation> found = invitationRepository.findByToken(invitation.token());

        // Then
        assertThat(found).isPresent().contains(invitation);
    }

    @Test
    void shouldReturnEmptyWhenInvitationNotFoundByToken() {
        // When
        Optional<Invitation> found = invitationRepository.findByToken("nonexistent-token");

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindPendingInvitationByEmail() {
        // Given
        String email = "test@example.com";
        Invitation invitation = Invitation.create(
            email,
            testRoleId,
            testUserId,
            24
        );
        invitationRepository.save(invitation);

        // When
        Optional<Invitation> found = invitationRepository.findPendingByEmail(email);

        // Then
        assertThat(found).isPresent().contains(invitation);
    }

    @Test
    void shouldNotFindNonPendingInvitationByEmail() {
        // Given
        String email = "test@example.com";
        Invitation invitation = Invitation.create(
            email,
            testRoleId,
            testUserId,
            24
        );
        Invitation accepted = invitation.accept();
        invitationRepository.save(accepted);

        // When
        Optional<Invitation> found = invitationRepository.findPendingByEmail(email);

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenNoPendingInvitationForEmail() {
        // When
        Optional<Invitation> found = invitationRepository.findPendingByEmail("nonexistent@example.com");

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindInvitationsByStatus() {
        // Given
        Invitation pending1 = Invitation.create("pending1@example.com", testRoleId, testUserId, 24);
        Invitation pending2 = Invitation.create("pending2@example.com", testRoleId, testUserId, 24);
        Invitation accepted = Invitation.create("accepted@example.com", testRoleId, testUserId, 24).accept();

        invitationRepository.save(pending1);
        invitationRepository.save(pending2);
        invitationRepository.save(accepted);

        // When
        List<Invitation> pendingInvitations = invitationRepository.findByStatusAndSince(InvitationStatus.PENDING, null);

        // Then
        assertThat(pendingInvitations).hasSize(2)
            .extracting(Invitation::email)
            .containsExactlyInAnyOrder("pending1@example.com", "pending2@example.com");
    }

    @Test
    void shouldFindInvitationsByStatusAndSince() {
        // Given
        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);

        Invitation recent = Invitation.create("recent@example.com", testRoleId, testUserId, 24);
        invitationRepository.save(recent);

        // When
        List<Invitation> recentInvitations = invitationRepository.findByStatusAndSince(InvitationStatus.PENDING, oneHourAgo);

        // Then
        assertThat(recentInvitations).hasSize(1)
            .extracting(Invitation::email)
            .containsExactly("recent@example.com");
    }

    @Test
    void shouldFindAllInvitationsWhenStatusIsNull() {
        // Given
        Invitation pending = Invitation.create("pending@example.com", testRoleId, testUserId, 24);
        Invitation accepted = Invitation.create("accepted@example.com", testRoleId, testUserId, 24).accept();

        invitationRepository.save(pending);
        invitationRepository.save(accepted);

        // When
        List<Invitation> allInvitations = invitationRepository.findByStatusAndSince(null, null);

        // Then
        assertThat(allInvitations).hasSize(2)
            .extracting(Invitation::email)
            .containsExactlyInAnyOrder("pending@example.com", "accepted@example.com");
    }

    @Test
    void shouldDeleteInvitation() {
        // Given
        Invitation invitation = Invitation.create(
            "test@example.com",
            testRoleId,
            testUserId,
            24
        );
        invitationRepository.save(invitation);

        // When
        invitationRepository.deleteById(invitation.id());

        // Then
        assertThat(invitationRepository.findById(invitation.id())).isEmpty();
    }

    @Test
    void shouldHandleEmailCaseInsensitivity() {
        // Given
        Invitation invitation = Invitation.create(
            "Test@Example.COM",
            testRoleId,
            testUserId,
            24
        );
        invitationRepository.save(invitation);

        // When
        Optional<Invitation> found = invitationRepository.findPendingByEmail("test@example.com");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().email()).isEqualTo("test@example.com");
    }
}
