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

import org.jwcarman.who.core.domain.ExternalIdentity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for persisting and retrieving external identities.
 */
public interface ExternalIdentityRepository {

    /**
     * Find an external identity by ID.
     *
     * @param id the external identity ID
     * @return the external identity, or empty if not found
     */
    Optional<ExternalIdentity> findById(UUID id);

    /**
     * Find an external identity by issuer and subject.
     *
     * @param issuer the JWT issuer (iss claim)
     * @param subject the JWT subject (sub claim)
     * @return the external identity, or empty if not found
     */
    Optional<ExternalIdentity> findByIssuerAndSubject(String issuer, String subject);

    /**
     * Find all external identities for a user.
     *
     * @param userId the user ID
     * @return list of external identities
     */
    List<ExternalIdentity> findByUserId(UUID userId);

    /**
     * Count external identities for a user.
     *
     * @param userId the user ID
     * @return count of external identities
     */
    long countByUserId(UUID userId);

    /**
     * Save an external identity (create or update).
     *
     * @param identity the external identity to save
     * @return the saved external identity
     */
    ExternalIdentity save(ExternalIdentity identity);

    /**
     * Delete an external identity.
     *
     * @param id the external identity ID
     */
    void deleteById(UUID id);
}
