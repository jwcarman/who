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
 */
@RestController
@RequestMapping("${who.web.mount-point:/api/who}/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /**
     * Create a new invitation.
     *
     * @param request the invitation request
     * @return the created invitation
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('who.invitation.create')")
    public InvitationResponse createInvitation(@RequestBody CreateInvitationRequest request) {
        Invitation invitation = invitationService.create(request.email(), request.roleId());
        return toResponse(invitation);
    }

    /**
     * Accept an invitation.
     *
     * @param token the invitation token
     * @return the accepted invitation
     */
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.OK)
    public InvitationResponse acceptInvitation(@RequestParam String token) {
        Invitation invitation = invitationService.accept(token);
        return toResponse(invitation);
    }

    /**
     * Revoke an invitation.
     *
     * @param invitationId the invitation ID
     */
    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.invitation.revoke')")
    public void revokeInvitation(@PathVariable UUID invitationId) {
        invitationService.revoke(invitationId);
    }

    /**
     * List invitations with optional filtering.
     *
     * @param status filter by status (optional)
     * @param since filter by created after this time (optional)
     * @return list of invitations
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
     * Get invitation by token (for validation).
     *
     * @param token the invitation token
     * @return the invitation if found, null otherwise
     */
    @GetMapping("/{token}")
    public InvitationResponse getInvitationByToken(@PathVariable String token) {
        return invitationService.findByToken(token)
                .map(this::toResponse)
                .orElse(null);
    }

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

    record CreateInvitationRequest(String email, UUID roleId) {}

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
