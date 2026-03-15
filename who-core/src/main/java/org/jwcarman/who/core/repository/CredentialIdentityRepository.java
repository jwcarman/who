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

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing the mapping between credential UUIDs and identity UUIDs.
 */
public interface CredentialIdentityRepository {

    /**
     * Finds the identity UUID linked to the given credential UUID.
     *
     * @param credentialId the credential UUID
     * @return the linked identity UUID, or empty if not linked
     */
    Optional<UUID> findIdentityIdByCredentialId(UUID credentialId);

    /**
     * Links the given credential to the given identity.
     *
     * @param credentialId the credential UUID
     * @param identityId   the identity UUID
     */
    void link(UUID credentialId, UUID identityId);

    /**
     * Removes the link from the given credential to its identity.
     *
     * @param credentialId the credential UUID
     */
    void unlink(UUID credentialId);
}
