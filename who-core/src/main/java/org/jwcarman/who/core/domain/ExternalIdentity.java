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

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable external identity domain model.
 * Links external JWT identities (issuer + subject) to internal user IDs.
 */
public record ExternalIdentity(
    UUID id,
    UUID userId,
    String issuer,
    String subject
) {
    public ExternalIdentity {
        requireNonNull(id, "id must not be null");
        requireNonNull(userId, "userId must not be null");
        requireNonNull(issuer, "issuer must not be null");
        requireNonNull(subject, "subject must not be null");
        if (issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }

    /**
     * Create a new external identity.
     *
     * @param id the external identity ID
     * @param userId the internal user ID
     * @param issuer the JWT issuer (iss claim)
     * @param subject the JWT subject (sub claim)
     * @return a new external identity
     */
    public static ExternalIdentity create(UUID id, UUID userId, String issuer, String subject) {
        return new ExternalIdentity(id, userId, issuer, subject);
    }

    /**
     * Create identity key for this external identity.
     *
     * @return the identity key
     */
    public ExternalIdentityKey toKey() {
        return new ExternalIdentityKey(issuer, subject);
    }
}
