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
package org.jwcarman.who.core.service;

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing user invitations.
 * Extracts current user (WhoPrincipal) from Spring SecurityContext for audit fields.
 */
public interface InvitationService {

    /**
     * Create invitation for email with role.
     * Auto-revokes any existing PENDING invitation for this email.
     * Current user extracted from SecurityContext.
     *
     * @param email the email to invite (will be normalized)
     * @param roleId the role to assign on acceptance
     * @return the created invitation
     * @throws org.jwcarman.who.core.exception.UserAlreadyExistsException if user already exists with this email
     */
    Invitation create(String email, UUID roleId);

    /**
     * Accept invitation after OAuth authentication.
     * Extracts JWT claims from SecurityContext (JwtAuthenticationToken).
     * Creates User, links ExternalIdentity, assigns Role, optionally creates verified ContactMethod.
     *
     * @param token the invitation token
     * @return the accepted invitation
     * @throws org.jwcarman.who.core.exception.InvitationNotFoundException if token invalid
     * @throws org.jwcarman.who.core.exception.InvitationExpiredException if expired
     * @throws org.jwcarman.who.core.exception.InvitationAlreadyAcceptedException if already used
     * @throws org.jwcarman.who.core.exception.EmailMismatchException if JWT email doesn't match invitation email
     * @throws org.jwcarman.who.core.exception.EmailNotVerifiedException if require-verified-email is true but email not verified
     * @throws IllegalStateException if SecurityContext doesn't contain JwtAuthenticationToken
     */
    Invitation accept(String token);

    /**
     * Revoke pending invitation.
     * Current user extracted from SecurityContext.
     *
     * @param invitationId the invitation to revoke
     * @throws org.jwcarman.who.core.exception.InvitationNotFoundException if not found
     */
    void revoke(UUID invitationId);

    /**
     * List invitations with optional filtering.
     *
     * @param status filter by status (null for all)
     * @param since filter by created after this time (null for all)
     * @return list of invitations
     */
    List<Invitation> list(InvitationStatus status, Instant since);

    /**
     * Get invitation by token (for validation).
     *
     * @param token the invitation token
     * @return the invitation if found
     */
    Optional<Invitation> findByToken(String token);
}
