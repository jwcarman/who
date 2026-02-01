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
import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;
import org.jwcarman.who.core.service.InvitationService;
import org.jwcarman.who.web.InvitationController.CreateInvitationRequest;
import org.jwcarman.who.web.InvitationController.InvitationResponse;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationControllerTest {

    @Mock
    private InvitationService invitationService;

    @InjectMocks
    private InvitationController controller;

    @Test
    void shouldCreateInvitation() {
        // Given
        String email = "user@example.com";
        UUID roleId = UUID.randomUUID();
        Invitation invitation = createInvitation(email, roleId);
        when(invitationService.create(email, roleId)).thenReturn(invitation);

        // When
        InvitationResponse response = controller.createInvitation(new CreateInvitationRequest(email, roleId));

        // Then
        assertThat(response.id()).isEqualTo(invitation.id());
        assertThat(response.email()).isEqualTo(invitation.email());
        assertThat(response.roleId()).isEqualTo(invitation.roleId());
        assertThat(response.token()).isEqualTo(invitation.token());
        assertThat(response.status()).isEqualTo(invitation.status());
        assertThat(response.invitedBy()).isEqualTo(invitation.invitedBy());
        assertThat(response.createdAt()).isEqualTo(invitation.createdAt());
        assertThat(response.expiresAt()).isEqualTo(invitation.expiresAt());
        assertThat(response.acceptedAt()).isEqualTo(invitation.acceptedAt());
        verify(invitationService).create(email, roleId);
    }

    @Test
    void shouldAcceptInvitation() {
        // Given
        String token = "test-token";
        Invitation invitation = createInvitation("user@example.com", UUID.randomUUID());
        when(invitationService.accept(token)).thenReturn(invitation);

        // When
        InvitationResponse response = controller.acceptInvitation(token);

        // Then
        assertThat(response.id()).isEqualTo(invitation.id());
        verify(invitationService).accept(token);
    }

    @Test
    void shouldRevokeInvitation() {
        // Given
        UUID invitationId = UUID.randomUUID();

        // When
        controller.revokeInvitation(invitationId);

        // Then
        verify(invitationService).revoke(invitationId);
    }

    @Test
    void shouldListInvitations() {
        // Given
        Invitation invitation1 = createInvitation("user1@example.com", UUID.randomUUID());
        Invitation invitation2 = createInvitation("user2@example.com", UUID.randomUUID());
        List<Invitation> invitations = List.of(invitation1, invitation2);
        when(invitationService.list(null, null)).thenReturn(invitations);

        // When
        List<InvitationResponse> response = controller.listInvitations(null, null);

        // Then
        assertThat(response).hasSize(2);
        assertThat(response.get(0).email()).isEqualTo(invitation1.email());
        assertThat(response.get(1).email()).isEqualTo(invitation2.email());
        verify(invitationService).list(null, null);
    }

    @Test
    void shouldListInvitationsWithFilters() {
        // Given
        Instant since = Instant.now().minusSeconds(3600);
        InvitationStatus status = InvitationStatus.PENDING;
        Invitation invitation = createInvitation("user@example.com", UUID.randomUUID());
        List<Invitation> invitations = List.of(invitation);
        when(invitationService.list(status, since)).thenReturn(invitations);

        // When
        List<InvitationResponse> response = controller.listInvitations(status, since);

        // Then
        assertThat(response).hasSize(1);
        verify(invitationService).list(status, since);
    }

    @Test
    void shouldGetInvitationByToken() {
        // Given
        String token = "test-token";
        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            "user@example.com",
            UUID.randomUUID(),
            token,
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(86400),
            null
        );
        when(invitationService.findByToken(token)).thenReturn(Optional.of(invitation));

        // When
        InvitationResponse response = controller.getInvitationByToken(token);

        // Then
        assertThat(response.token()).isEqualTo(token);
        verify(invitationService).findByToken(token);
    }

    @Test
    void shouldReturnNullWhenInvitationNotFoundByToken() {
        // Given
        String token = "invalid-token";
        when(invitationService.findByToken(token)).thenReturn(Optional.empty());

        // When
        InvitationResponse response = controller.getInvitationByToken(token);

        // Then
        assertThat(response).isNull();
        verify(invitationService).findByToken(token);
    }

    private Invitation createInvitation(String email, UUID roleId) {
        return new Invitation(
            UUID.randomUUID(),
            email,
            roleId,
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(86400),
            null
        );
    }
}
