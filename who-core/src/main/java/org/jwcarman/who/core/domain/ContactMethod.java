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
 * Immutable contact method domain model.
 *
 * @param id unique identifier
 * @param userId the user who owns this contact method
 * @param type EMAIL or PHONE
 * @param value normalized contact value (lowercase email, E.164 phone)
 * @param verified true if user confirmed ownership
 * @param verifiedAt when verification completed (null if unverified)
 * @param createdAt when contact method was created
 */
public record ContactMethod(
    UUID id,
    UUID userId,
    ContactType type,           // EMAIL, PHONE
    String value,               // Normalized (lowercase email, E.164 phone)
    boolean verified,           // True if user confirmed ownership
    Instant verifiedAt,         // When verification completed (null if unverified)
    Instant createdAt
) {
    /**
     * Compact constructor with validation.
     */
    public ContactMethod {
        requireNonNull(id, "id must not be null");
        requireNonNull(userId, "userId must not be null");
        requireNonNull(type, "type must not be null");
        requireNonNull(value, "value must not be null");
        requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Create unverified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value (will be normalized)
     * @return new unverified contact method
     */
    public static ContactMethod createUnverified(final UUID userId,
                                                   final ContactType type,
                                                   final String value) {
        return new ContactMethod(
            UUID.randomUUID(),
            userId,
            type,
            normalize(value, type),
            false,
            null,
            Instant.now()
        );
    }

    /**
     * Create verified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value (will be normalized)
     * @return new verified contact method
     */
    public static ContactMethod createVerified(final UUID userId,
                                                 final ContactType type,
                                                 final String value) {
        Instant now = Instant.now();
        return new ContactMethod(
            UUID.randomUUID(),
            userId,
            type,
            normalize(value, type),
            true,
            now,
            now
        );
    }

    /**
     * Mark contact method as verified.
     *
     * @return new contact method with verified=true and verifiedAt timestamp
     */
    public ContactMethod markVerified() {
        return new ContactMethod(
            id, userId, type, value,
            true,
            Instant.now(),
            createdAt
        );
    }

    /**
     * Normalize contact value based on type.
     *
     * @param value the raw value
     * @param type the contact type
     * @return normalized value
     */
    private static String normalize(final String value, final ContactType type) {
        requireNonNull(value, "value must not be null");
        return switch (type) {
            case EMAIL -> value.toLowerCase().trim();
            case PHONE -> value.trim();  // Phone normalization (E.164) left for future
        };
    }
}
