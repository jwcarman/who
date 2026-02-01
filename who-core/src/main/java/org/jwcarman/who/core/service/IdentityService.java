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

import java.util.UUID;

/**
 * Service for managing external identity federation.
 */
public interface IdentityService {

    /**
     * Link an external identity to an internal user.
     *
     * @param userId the internal user ID
     * @param issuer the JWT issuer (iss claim)
     * @param subject the JWT subject (sub claim)
     * @throws IllegalArgumentException if the identity is already linked to another user
     * @throws IllegalArgumentException if the user does not exist
     */
    void linkExternalIdentity(UUID userId, String issuer, String subject);

    /**
     * Unlink an external identity from a user.
     *
     * @param userId the internal user ID
     * @param externalIdentityId the ID of the external identity to unlink
     * @throws IllegalArgumentException if the user does not exist
     * @throws IllegalArgumentException if the external identity does not exist or is not linked to the user
     */
    void unlinkExternalIdentity(UUID userId, UUID externalIdentityId);
}
