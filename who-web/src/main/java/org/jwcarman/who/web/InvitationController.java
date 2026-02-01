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

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;
import org.jwcarman.who.core.service.InvitationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing invitations.
 * <p>
 * This controller provides endpoints for the invitation lifecycle: creating, accepting, revoking,
 * and listing invitations. Invitations allow administrators to invite new users by email and
 * automatically assign them to roles upon acceptance.
 * <p>
 * The base path is configurable via {@code who.web.mount-point} property (defaults to {@code /api/who}).
 */
@RestController
@RequestMapping("${who.web.mount-point:/api/who}/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    /**
     * Constructs a new InvitationController with required services.
     *
     * @param invitationService the invitation service for managing invitation lifecycle
     */
    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * Creates a new invitation for a user.
     * <p>
     * HTTP POST to {@code /invitations}
     * <p>
     * Sends a notification to the specified email address containing a unique invitation token.
     * The recipient can use this token to accept the invitation and be provisioned with the
     * specified role.
     * <p>
     * Requires permission: {@code who.invitation.create}
     *
     * @param request the invitation request containing email and role ID
     * @return the created invitation with generated token and expiration
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('who.invitation.create')")
    public InvitationResponse createInvitation(@RequestBody CreateInvitationRequest request) {
        Invitation invitation = invitationService.create(request.email(), request.roleId());
        return toResponse(invitation);
    }

    /**
     * Accepts an invitation using the provided token.
     * <p>
     * HTTP POST to {@code /invitations/accept}
     * <p>
     * This endpoint is typically called by the invited user after receiving the invitation email.
     * Accepting an invitation provisions a new user account or links an existing external identity
     * to the system, and assigns the specified role.
     * <p>
     * This endpoint does not require authentication, as it uses the invitation token for authorization.
     *
     * @param token the unique invitation token received via email
     * @return the accepted invitation with updated status and acceptedAt timestamp
     * @throws org.jwcarman.who.core.exception.InvitationExpiredException if the invitation has expired
     * @throws org.jwcarman.who.core.exception.InvitationAlreadyAcceptedException if already accepted
     */
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.OK)
    public InvitationResponse acceptInvitation(@RequestParam String token) {
        Invitation invitation = invitationService.accept(token);
        return toResponse(invitation);
    }

    /**
     * Revokes an invitation before it has been accepted.
     * <p>
     * HTTP DELETE to {@code /invitations/{invitationId}}
     * <p>
     * Once revoked, the invitation token can no longer be used to accept the invitation.
     * <p>
     * Requires permission: {@code who.invitation.revoke}
     *
     * @param invitationId the ID of the invitation to revoke
     */
    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.invitation.revoke')")
    public void revokeInvitation(@PathVariable UUID invitationId) {
        invitationService.revoke(invitationId);
    }

    /**
     * Lists invitations with optional filtering.
     * <p>
     * HTTP GET to {@code /invitations}
     * <p>
     * Supports filtering by status and creation time to retrieve specific subsets of invitations.
     * <p>
     * Requires permission: {@code who.invitation.list}
     *
     * @param status optional filter by invitation status (PENDING, ACCEPTED, EXPIRED, REVOKED)
     * @param since optional filter to only include invitations created after this timestamp
     * @return list of invitations matching the filter criteria
     */
    @GetMapping
    @PreAuthorize("hasAuthority('who.invitation.list')")
    public List<InvitationResponse> listInvitations(
            @RequestParam(required = false) InvitationStatus status,
            @RequestParam(required = false) Instant since) {
        return invitationService.list(status, since).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Retrieves an invitation by its token for validation purposes.
     * <p>
     * HTTP GET to {@code /invitations/{token}}
     * <p>
     * This endpoint can be used to validate an invitation token and retrieve its details
     * before attempting to accept it. Useful for displaying invitation information in a UI.
     * <p>
     * This endpoint does not require authentication.
     *
     * @param token the invitation token to look up
     * @return the invitation details if found and valid, null if not found
     */
    @GetMapping("/{token}")
    public InvitationResponse getInvitationByToken(@PathVariable String token) {
        return invitationService.findByToken(token)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * Converts an Invitation domain object to an InvitationResponse DTO.
     *
     * @param invitation the domain invitation object
     * @return the invitation response DTO
     */
    private InvitationResponse toResponse(Invitation invitation) {
        return new InvitationResponse(
                invitation.id(),
                invitation.email(),
                invitation.roleId(),
                invitation.token(),
                invitation.status(),
                invitation.invitedBy(),
                invitation.createdAt(),
                invitation.expiresAt(),
                invitation.acceptedAt()
        );
    }

    /**
     * Request to create a new invitation.
     *
     * @param email the email address of the user to invite
     * @param roleId the ID of the role to assign upon acceptance
     */
    record CreateInvitationRequest(String email, UUID roleId) {}

    /**
     * Response containing invitation details.
     *
     * @param id the unique identifier of the invitation
     * @param email the email address of the invited user
     * @param roleId the ID of the role to be assigned upon acceptance
     * @param token the unique token used to accept the invitation
     * @param status the current status of the invitation
     * @param invitedBy the user ID who created the invitation
     * @param createdAt the timestamp when the invitation was created
     * @param expiresAt the timestamp when the invitation expires
     * @param acceptedAt the timestamp when the invitation was accepted (null if not yet accepted)
     */
    record InvitationResponse(
            UUID id,
            String email,
            UUID roleId,
            String token,
            InvitationStatus status,
            UUID invitedBy,
            Instant createdAt,
            Instant expiresAt,
            Instant acceptedAt
    ) {}
}
