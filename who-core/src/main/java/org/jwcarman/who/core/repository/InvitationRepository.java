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
package org.jwcarman.who.core.repository;

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing invitations.
 */
public interface InvitationRepository {

    /**
     * Save an invitation.
     *
     * @param invitation the invitation to save
     * @return the saved invitation
     */
    Invitation save(Invitation invitation);

    /**
     * Find invitation by ID.
     *
     * @param id the invitation ID
     * @return the invitation if found
     */
    Optional<Invitation> findById(UUID id);

    /**
     * Find invitation by token.
     *
     * @param token the invitation token
     * @return the invitation if found
     */
    Optional<Invitation> findByToken(String token);

    /**
     * Find pending invitation by email.
     *
     * @param email the email address
     * @return the pending invitation if found
     */
    Optional<Invitation> findPendingByEmail(String email);

    /**
     * Find invitations by status.
     *
     * @param status the invitation status (null for all)
     * @param since filter by created after this time (null for all)
     * @return list of invitations
     */
    List<Invitation> findByStatusAndSince(InvitationStatus status, Instant since);

    /**
     * Delete an invitation.
     *
     * @param id the invitation ID
     */
    void deleteById(UUID id);
}
