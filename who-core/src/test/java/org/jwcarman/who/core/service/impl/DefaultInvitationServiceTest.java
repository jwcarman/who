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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;
import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.exception.EmailMismatchException;
import org.jwcarman.who.core.exception.EmailNotVerifiedException;
import org.jwcarman.who.core.exception.InvitationAlreadyAcceptedException;
import org.jwcarman.who.core.exception.InvitationExpiredException;
import org.jwcarman.who.core.exception.InvitationNotFoundException;
import org.jwcarman.who.core.exception.UserAlreadyExistsException;
import org.jwcarman.who.core.repository.ContactMethodRepository;
import org.jwcarman.who.core.repository.InvitationRepository;
import org.jwcarman.who.core.repository.RoleRepository;
import org.jwcarman.who.core.service.ContactMethodService;
import org.jwcarman.who.core.service.IdentityService;
import org.jwcarman.who.core.service.UserService;
import org.jwcarman.who.core.spi.InvitationNotifier;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultInvitationServiceTest {

    @Mock
    private InvitationRepository invitationRepository;

    @Mock
    private InvitationNotifier invitationNotifier;

    @Mock
    private UserService userService;

    @Mock
    private IdentityService identityService;

    @Mock
    private ContactMethodService contactMethodService;

    @Mock
    private ContactMethodRepository contactMethodRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    private DefaultInvitationService service;

    private static final int EXPIRATION_HOURS = 72;
    private static final boolean REQUIRE_VERIFIED_EMAIL = true;
    private static final boolean TRUST_ISSUER_VERIFICATION = true;

    @BeforeEach
    void setUp() {
        service = new DefaultInvitationService(
            invitationRepository,
            invitationNotifier,
            userService,
            identityService,
            contactMethodService,
            contactMethodRepository,
            roleRepository,
            EXPIRATION_HOURS,
            REQUIRE_VERIFIED_EMAIL,
            TRUST_ISSUER_VERIFICATION
        );

        SecurityContextHolder.setContext(securityContext);
    }

    // ==================== create() tests ====================

    @Test
    void create_shouldCreateInvitationAndNotify() {
        // Given
        UUID currentUserId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(currentUserId, Set.of());
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);

        String email = "alice@example.com";
        UUID roleId = UUID.randomUUID();

        // No existing user with this email
        when(contactMethodRepository.findByTypeAndValue(ContactType.EMAIL, email)).thenReturn(Optional.empty());

        // No existing pending invitation
        when(invitationRepository.findPendingByEmail(email)).thenReturn(Optional.empty());

        // Role exists
        when(roleRepository.existsById(roleId)).thenReturn(true);

        // Mock save
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Invitation result = service.create(email, roleId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.email()).isEqualTo(email);
        assertThat(result.roleId()).isEqualTo(roleId);
        assertThat(result.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(result.invitedBy()).isEqualTo(currentUserId);
        assertThat(result.token()).isNotNull();
        assertThat(result.acceptedAt()).isNull();

        ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitationRepository).save(captor.capture());
        verify(invitationNotifier).sendInvitation(captor.getValue());
    }

    @Test
    void create_shouldThrowWhenUserAlreadyExists() {
        // Given
        UUID currentUserId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(currentUserId, Set.of());
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);

        String email = "alice@example.com";
        UUID roleId = UUID.randomUUID();

        // User already exists with this email
        ContactMethod existingContact = ContactMethod.createVerified(UUID.randomUUID(), ContactType.EMAIL, email);
        when(contactMethodRepository.findByTypeAndValue(ContactType.EMAIL, email)).thenReturn(Optional.of(existingContact));

        // When/Then
        assertThatThrownBy(() -> service.create(email, roleId))
            .isInstanceOf(UserAlreadyExistsException.class)
            .hasMessageContaining(email);

        verify(invitationRepository, never()).save(any());
        verify(invitationNotifier, never()).sendInvitation(any());
    }

    @Test
    void create_shouldRevokeExistingPendingInvitation() {
        // Given
        UUID currentUserId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(currentUserId, Set.of());
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);

        String email = "alice@example.com";
        UUID roleId = UUID.randomUUID();

        // No existing user
        when(contactMethodRepository.findByTypeAndValue(ContactType.EMAIL, email)).thenReturn(Optional.empty());

        // Existing pending invitation
        Invitation existingInvitation = Invitation.create(email, UUID.randomUUID(), currentUserId, EXPIRATION_HOURS);
        when(invitationRepository.findPendingByEmail(email)).thenReturn(Optional.of(existingInvitation));

        // Role exists
        when(roleRepository.existsById(roleId)).thenReturn(true);

        // Mock save
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.create(email, roleId);

        // Then
        ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitationRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<Invitation> savedInvitations = captor.getAllValues();
        assertThat(savedInvitations).hasSize(2); // Revoked old + new invitation

        // First save should be the revoked invitation
        Invitation revokedInvitation = savedInvitations.get(0);
        assertThat(revokedInvitation.status()).isEqualTo(InvitationStatus.REVOKED);

        // Second save should be the new invitation
        Invitation newInvitation = savedInvitations.get(1);
        assertThat(newInvitation.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(newInvitation.email()).isEqualTo(email);
    }

    @Test
    void create_shouldNormalizeEmail() {
        // Given
        UUID currentUserId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(currentUserId, Set.of());
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);

        String email = "  Alice@EXAMPLE.COM  ";
        String normalizedEmail = "alice@example.com";
        UUID roleId = UUID.randomUUID();

        when(contactMethodRepository.findByTypeAndValue(ContactType.EMAIL, normalizedEmail)).thenReturn(Optional.empty());
        when(invitationRepository.findPendingByEmail(normalizedEmail)).thenReturn(Optional.empty());
        when(roleRepository.existsById(roleId)).thenReturn(true);
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Invitation result = service.create(email, roleId);

        // Then
        assertThat(result.email()).isEqualTo(normalizedEmail);
    }

    // ==================== accept() tests ====================

    @Test
    void accept_shouldAcceptInvitationAndCreateUser() {
        // Given
        String token = UUID.randomUUID().toString();
        String email = "alice@example.com";
        UUID roleId = UUID.randomUUID();
        UUID invitedBy = UUID.randomUUID();

        Invitation invitation = Invitation.create(email, roleId, invitedBy, EXPIRATION_HOURS);
        // Use reflection or create with specific token - for now, we'll mock the token match
        Invitation invitationWithToken = new Invitation(
            invitation.id(),
            email,
            roleId,
            token,
            InvitationStatus.PENDING,
            invitedBy,
            invitation.createdAt(),
            invitation.expiresAt(),
            null
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(invitationWithToken));

        // Setup JWT
        String issuer = "https://accounts.google.com";
        String subject = "google-oauth2|123456";
        Jwt jwt = createJwt(issuer, subject, email, true);
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt);
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);

        // Mock user creation
        UUID newUserId = UUID.randomUUID();
        when(userService.createUser(UserStatus.ACTIVE)).thenReturn(newUserId);

        // Mock contact creation
        ContactMethod contact = ContactMethod.createVerified(newUserId, ContactType.EMAIL, email);
        when(contactMethodService.createVerified(newUserId, ContactType.EMAIL, email)).thenReturn(contact);

        // Mock save
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Invitation result = service.accept(token);

        // Then
        assertThat(result.status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(result.acceptedAt()).isNotNull();

        verify(userService).createUser(UserStatus.ACTIVE);
        verify(identityService).linkExternalIdentity(newUserId, issuer, subject);
        verify(userService).assignRoleToUser(newUserId, roleId);
        verify(contactMethodService).createVerified(newUserId, ContactType.EMAIL, email);
    }

    @Test
    void accept_shouldThrowWhenInvitationNotFound() {
        // Given
        String token = UUID.randomUUID().toString();
        when(invitationRepository.findByToken(token)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> service.accept(token))
            .isInstanceOf(InvitationNotFoundException.class);

        verify(userService, never()).createUser(any());
    }

    @Test
    void accept_shouldThrowWhenInvitationExpired() {
        // Given
        String token = UUID.randomUUID().toString();
        String email = "alice@example.com";

        // Create expired invitation
        Invitation expiredInvitation = new Invitation(
            UUID.randomUUID(),
            email,
            UUID.randomUUID(),
            token,
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now().minusSeconds(200),
            Instant.now().minusSeconds(10), // Expired 10 seconds ago
            null
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(expiredInvitation));

        // When/Then
        assertThatThrownBy(() -> service.accept(token))
            .isInstanceOf(InvitationExpiredException.class);

        verify(userService, never()).createUser(any());
    }

    @Test
    void accept_shouldThrowWhenAlreadyAccepted() {
        // Given
        String token = UUID.randomUUID().toString();
        String email = "alice@example.com";

        // Already accepted invitation
        Invitation acceptedInvitation = new Invitation(
            UUID.randomUUID(),
            email,
            UUID.randomUUID(),
            token,
            InvitationStatus.ACCEPTED,
            UUID.randomUUID(),
            Instant.now().minusSeconds(100),
            Instant.now().plusSeconds(3600),
            Instant.now().minusSeconds(50)
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(acceptedInvitation));

        // When/Then
        assertThatThrownBy(() -> service.accept(token))
            .isInstanceOf(InvitationAlreadyAcceptedException.class);

        verify(userService, never()).createUser(any());
    }

    @Test
    void accept_shouldThrowWhenEmailDoesNotMatch() {
        // Given
        String token = UUID.randomUUID().toString();
        String invitationEmail = "alice@example.com";
        String jwtEmail = "bob@example.com";

        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            invitationEmail,
            UUID.randomUUID(),
            token,
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            null
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(invitation));

        // Setup JWT with different email
        Jwt jwt = createJwt("https://accounts.google.com", "123", jwtEmail, true);
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt);
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);

        // When/Then
        assertThatThrownBy(() -> service.accept(token))
            .isInstanceOf(EmailMismatchException.class)
            .hasMessageContaining(invitationEmail)
            .hasMessageContaining(jwtEmail);

        verify(userService, never()).createUser(any());
    }

    @Test
    void accept_shouldThrowWhenEmailNotVerifiedAndRequired() {
        // Given
        String token = UUID.randomUUID().toString();
        String email = "alice@example.com";

        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            email,
            UUID.randomUUID(),
            token,
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            null
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(invitation));

        // Setup JWT with email_verified = false
        Jwt jwt = createJwt("https://accounts.google.com", "123", email, false);
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt);
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);

        // When/Then (service is configured with requireVerifiedEmail = true)
        assertThatThrownBy(() -> service.accept(token))
            .isInstanceOf(EmailNotVerifiedException.class)
            .hasMessageContaining(email);

        verify(userService, never()).createUser(any());
    }

    @Test
    void accept_shouldCreateUnverifiedContactWhenTrustIssuerVerificationIsFalse() {
        // Given
        // Create service with trustIssuerVerification = false
        DefaultInvitationService serviceNoTrust = new DefaultInvitationService(
            invitationRepository,
            invitationNotifier,
            userService,
            identityService,
            contactMethodService,
            contactMethodRepository,
            roleRepository,
            EXPIRATION_HOURS,
            false, // Don't require verified email
            false  // Don't trust issuer verification
        );

        String token = UUID.randomUUID().toString();
        String email = "alice@example.com";
        UUID roleId = UUID.randomUUID();

        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            email,
            roleId,
            token,
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            null
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(invitation));

        // Setup JWT
        Jwt jwt = createJwt("https://accounts.google.com", "123", email, true);
        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt);
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);

        UUID newUserId = UUID.randomUUID();
        when(userService.createUser(UserStatus.ACTIVE)).thenReturn(newUserId);

        ContactMethod contact = ContactMethod.createUnverified(newUserId, ContactType.EMAIL, email);
        when(contactMethodService.createUnverified(newUserId, ContactType.EMAIL, email)).thenReturn(contact);
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        serviceNoTrust.accept(token);

        // Then
        verify(contactMethodService).createUnverified(newUserId, ContactType.EMAIL, email);
        verify(contactMethodService, never()).createVerified(any(), any(), any());
    }

    @Test
    void accept_shouldThrowWhenSecurityContextDoesNotContainJwt() {
        // Given
        String token = UUID.randomUUID().toString();
        String email = "alice@example.com";

        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            email,
            UUID.randomUUID(),
            token,
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            null
        );

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(invitation));

        // Setup non-JWT authentication
        when(securityContext.getAuthentication()).thenReturn(authentication);

        // When/Then
        assertThatThrownBy(() -> service.accept(token))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JwtAuthenticationToken");

        verify(userService, never()).createUser(any());
    }

    // ==================== revoke() tests ====================

    @Test
    void revoke_shouldRevokeInvitation() {
        // Given
        UUID invitationId = UUID.randomUUID();
        String email = "alice@example.com";

        Invitation invitation = Invitation.create(email, UUID.randomUUID(), UUID.randomUUID(), EXPIRATION_HOURS);
        Invitation invitationWithId = new Invitation(
            invitationId,
            invitation.email(),
            invitation.roleId(),
            invitation.token(),
            invitation.status(),
            invitation.invitedBy(),
            invitation.createdAt(),
            invitation.expiresAt(),
            null
        );

        when(invitationRepository.findById(invitationId)).thenReturn(Optional.of(invitationWithId));
        when(invitationRepository.save(any(Invitation.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.revoke(invitationId);

        // Then
        ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitationRepository).save(captor.capture());

        Invitation revokedInvitation = captor.getValue();
        assertThat(revokedInvitation.status()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    void revoke_shouldThrowWhenInvitationNotFound() {
        // Given
        UUID invitationId = UUID.randomUUID();
        when(invitationRepository.findById(invitationId)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> service.revoke(invitationId))
            .isInstanceOf(InvitationNotFoundException.class);

        verify(invitationRepository, never()).save(any());
    }

    // ==================== list() tests ====================

    @Test
    void list_shouldDelegateToRepository() {
        // Given
        InvitationStatus status = InvitationStatus.PENDING;
        Instant since = Instant.now().minusSeconds(3600);

        List<Invitation> invitations = List.of(
            Invitation.create("alice@example.com", UUID.randomUUID(), UUID.randomUUID(), EXPIRATION_HOURS),
            Invitation.create("bob@example.com", UUID.randomUUID(), UUID.randomUUID(), EXPIRATION_HOURS)
        );

        when(invitationRepository.findByStatusAndSince(status, since)).thenReturn(invitations);

        // When
        List<Invitation> result = service.list(status, since);

        // Then
        assertThat(result).isEqualTo(invitations);
        verify(invitationRepository).findByStatusAndSince(status, since);
    }

    @Test
    void list_shouldAllowNullParameters() {
        // Given
        List<Invitation> invitations = List.of(
            Invitation.create("alice@example.com", UUID.randomUUID(), UUID.randomUUID(), EXPIRATION_HOURS)
        );

        when(invitationRepository.findByStatusAndSince(null, null)).thenReturn(invitations);

        // When
        List<Invitation> result = service.list(null, null);

        // Then
        assertThat(result).isEqualTo(invitations);
        verify(invitationRepository).findByStatusAndSince(null, null);
    }

    // ==================== findByToken() tests ====================

    @Test
    void findByToken_shouldDelegateToRepository() {
        // Given
        String token = UUID.randomUUID().toString();
        Invitation invitation = Invitation.create("alice@example.com", UUID.randomUUID(), UUID.randomUUID(), EXPIRATION_HOURS);

        when(invitationRepository.findByToken(token)).thenReturn(Optional.of(invitation));

        // When
        Optional<Invitation> result = service.findByToken(token);

        // Then
        assertThat(result).contains(invitation);
        verify(invitationRepository).findByToken(token);
    }

    @Test
    void findByToken_shouldReturnEmptyWhenNotFound() {
        // Given
        String token = UUID.randomUUID().toString();
        when(invitationRepository.findByToken(token)).thenReturn(Optional.empty());

        // When
        Optional<Invitation> result = service.findByToken(token);

        // Then
        assertThat(result).isEmpty();
        verify(invitationRepository).findByToken(token);
    }

    // ==================== Helper methods ====================

    private Jwt createJwt(String issuer, String subject, String email, boolean emailVerified) {
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("iss", issuer)
            .claim("sub", subject)
            .claim("email", email)
            .claim("email_verified", emailVerified)
            .build();
    }
}
