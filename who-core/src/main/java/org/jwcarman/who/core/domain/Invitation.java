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
package org.jwcarman.who.core.domain;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable invitation domain model.
 *
 * @param id the unique identifier for the invitation
 * @param email the invited email (normalized: lowercase, trimmed)
 * @param roleId the role to assign on acceptance
 * @param token the unique acceptance token (random UUID)
 * @param status the invitation status
 * @param invitedBy the admin user who created the invitation
 * @param createdAt the timestamp when the invitation was created
 * @param expiresAt the timestamp when the invitation expires
 * @param acceptedAt the accepted timestamp (null until accepted)
 */
public record Invitation(
    UUID id,
    String email,
    UUID roleId,
    String token,
    InvitationStatus status,
    UUID invitedBy,
    Instant createdAt,
    Instant expiresAt,
    Instant acceptedAt
) {
    /**
     * Number of seconds in one hour.
     */
    private static final long SECONDS_PER_HOUR = 3600L;

    /**
     * Compact constructor for validation.
     */
    public Invitation {
        requireNonNull(id, "id must not be null");
        requireNonNull(email, "email must not be null");
        requireNonNull(roleId, "roleId must not be null");
        requireNonNull(token, "token must not be null");
        requireNonNull(status, "status must not be null");
        requireNonNull(invitedBy, "invitedBy must not be null");
        requireNonNull(createdAt, "createdAt must not be null");
        requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * Check if invitation has expired.
     *
     * @return true if current time is after expiration time
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if invitation is pending (PENDING status and not expired).
     *
     * @return true if pending and not expired
     */
    public boolean isPending() {
        return status == InvitationStatus.PENDING && !isExpired();
    }

    /**
     * Create a new pending invitation.
     *
     * @param email the email to invite
     * @param roleId the role to assign
     * @param invitedBy the admin creating the invitation
     * @param expirationHours hours until expiration
     * @return new pending invitation
     */
    public static Invitation create(
            final String email,
            final UUID roleId,
            final UUID invitedBy,
            final int expirationHours) {
        Instant now = Instant.now();
        return new Invitation(
            UUID.randomUUID(),
            email.toLowerCase().trim(),
            roleId,
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            invitedBy,
            now,
            now.plusSeconds(expirationHours * SECONDS_PER_HOUR),
            null
        );
    }

    /**
     * Mark invitation as accepted.
     *
     * @return new invitation with ACCEPTED status and acceptedAt timestamp
     */
    public Invitation accept() {
        return new Invitation(
            id, email, roleId, token,
            InvitationStatus.ACCEPTED,
            invitedBy, createdAt, expiresAt,
            Instant.now()
        );
    }

    /**
     * Mark invitation as revoked.
     *
     * @return new invitation with REVOKED status
     */
    public Invitation revoke() {
        return new Invitation(
            id, email, roleId, token,
            InvitationStatus.REVOKED,
            invitedBy, createdAt, expiresAt,
            acceptedAt
        );
    }
}
