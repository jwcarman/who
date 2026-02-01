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
import org.jwcarman.who.core.service.InvitationService;
import org.jwcarman.who.core.service.UserService;
import org.jwcarman.who.core.spi.InvitationNotifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of InvitationService.
 * Integrates with Spring Security for authentication context.
 */
public class DefaultInvitationService implements InvitationService {

    private final InvitationRepository invitationRepository;
    private final InvitationNotifier invitationNotifier;
    private final UserService userService;
    private final IdentityService identityService;
    private final ContactMethodService contactMethodService;
    private final ContactMethodRepository contactMethodRepository;
    private final RoleRepository roleRepository;
    private final int expirationHours;
    private final boolean requireVerifiedEmail;
    private final boolean trustIssuerVerification;

    public DefaultInvitationService(
            InvitationRepository invitationRepository,
            InvitationNotifier invitationNotifier,
            UserService userService,
            IdentityService identityService,
            ContactMethodService contactMethodService,
            ContactMethodRepository contactMethodRepository,
            RoleRepository roleRepository,
            int expirationHours,
            boolean requireVerifiedEmail,
            boolean trustIssuerVerification) {
        this.invitationRepository = invitationRepository;
        this.invitationNotifier = invitationNotifier;
        this.userService = userService;
        this.identityService = identityService;
        this.contactMethodService = contactMethodService;
        this.contactMethodRepository = contactMethodRepository;
        this.roleRepository = roleRepository;
        this.expirationHours = expirationHours;
        this.requireVerifiedEmail = requireVerifiedEmail;
        this.trustIssuerVerification = trustIssuerVerification;
    }

    @Override
    public Invitation create(String email, UUID roleId) {
        // Normalize email
        String normalizedEmail = email.toLowerCase().trim();

        // Extract current user from SecurityContext
        UUID currentUserId = getCurrentUserId();

        // Check if user already exists with this email
        Optional<ContactMethod> existingContact = contactMethodRepository
            .findByTypeAndValue(ContactType.EMAIL, normalizedEmail);
        if (existingContact.isPresent()) {
            throw new UserAlreadyExistsException(
                "User already exists with email: " + normalizedEmail
            );
        }

        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Auto-revoke any existing PENDING invitation for this email
        Optional<Invitation> existingInvitation = invitationRepository.findPendingByEmail(normalizedEmail);
        existingInvitation.ifPresent(invitation -> {
            Invitation revoked = invitation.revoke();
            invitationRepository.save(revoked);
        });

        // Create new invitation
        Invitation invitation = Invitation.create(
            normalizedEmail,
            roleId,
            currentUserId,
            expirationHours
        );

        // Save invitation
        invitation = invitationRepository.save(invitation);

        // Send notification
        invitationNotifier.sendInvitation(invitation);

        return invitation;
    }

    @Override
    public Invitation accept(String token) {
        // Find invitation by token
        Invitation invitation = invitationRepository.findByToken(token)
            .orElseThrow(() -> new InvitationNotFoundException(
                "Invitation not found with token: " + token
            ));

        // Check if expired
        if (invitation.isExpired()) {
            throw new InvitationExpiredException(
                "Invitation has expired for email: " + invitation.email()
            );
        }

        // Check if already accepted
        if (invitation.status() == InvitationStatus.ACCEPTED) {
            throw new InvitationAlreadyAcceptedException(
                "Invitation has already been accepted for email: " + invitation.email()
            );
        }

        // Extract JWT from SecurityContext
        Jwt jwt = extractJwtFromSecurityContext();

        // Extract claims from JWT
        String jwtEmail = jwt.getClaimAsString("email");
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getClaimAsString("sub");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");

        // Verify email matches
        String normalizedJwtEmail = jwtEmail != null ? jwtEmail.toLowerCase().trim() : null;
        if (!invitation.email().equals(normalizedJwtEmail)) {
            throw new EmailMismatchException(
                "JWT email (" + jwtEmail + ") does not match invitation email (" + invitation.email() + ")"
            );
        }

        // Check email verification if required
        if (requireVerifiedEmail && (emailVerified == null || !emailVerified)) {
            throw new EmailNotVerifiedException(
                "Email not verified for: " + invitation.email()
            );
        }

        // Create user with ACTIVE status
        UUID userId = userService.createUser(UserStatus.ACTIVE);

        // Link external identity
        identityService.linkExternalIdentity(userId, issuer, subject);

        // Assign role
        userService.assignRoleToUser(userId, invitation.roleId());

        // Create contact method (verified or unverified based on configuration)
        if (trustIssuerVerification && emailVerified != null && emailVerified) {
            contactMethodService.createVerified(userId, ContactType.EMAIL, invitation.email());
        } else {
            contactMethodService.createUnverified(userId, ContactType.EMAIL, invitation.email());
        }

        // Mark invitation as accepted
        Invitation acceptedInvitation = invitation.accept();
        return invitationRepository.save(acceptedInvitation);
    }

    @Override
    public void revoke(UUID invitationId) {
        // Find invitation
        Invitation invitation = invitationRepository.findById(invitationId)
            .orElseThrow(() -> new InvitationNotFoundException(
                "Invitation not found with id: " + invitationId
            ));

        // Revoke invitation
        Invitation revokedInvitation = invitation.revoke();
        invitationRepository.save(revokedInvitation);
    }

    @Override
    public List<Invitation> list(InvitationStatus status, Instant since) {
        return invitationRepository.findByStatusAndSince(status, since);
    }

    @Override
    public Optional<Invitation> findByToken(String token) {
        return invitationRepository.findByToken(token);
    }

    /**
     * Extract current user ID from SecurityContext.
     *
     * @return the current user ID
     * @throws IllegalStateException if no authentication or principal is not WhoPrincipal
     */
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("No authentication found in SecurityContext");
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof WhoPrincipal whoPrincipal)) {
            throw new IllegalStateException(
                "Expected WhoPrincipal but found: " + principal.getClass().getName()
            );
        }

        return whoPrincipal.userId();
    }

    /**
     * Extract JWT from SecurityContext.
     *
     * @return the JWT
     * @throws IllegalStateException if authentication is not JwtAuthenticationToken
     */
    private Jwt extractJwtFromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                "Expected JwtAuthenticationToken but found: " +
                (authentication != null ? authentication.getClass().getName() : "null")
            );
        }

        return jwtAuth.getToken();
    }
}
