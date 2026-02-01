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

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing user contact methods.
 */
public interface ContactMethodService {

    /**
     * Create unverified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value
     * @return the created contact method
     */
    ContactMethod createUnverified(UUID userId, ContactType type, String value);

    /**
     * Create verified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value
     * @return the created contact method
     */
    ContactMethod createVerified(UUID userId, ContactType type, String value);

    /**
     * Mark contact method as verified.
     *
     * @param contactMethodId the contact method ID
     * @return the verified contact method
     * @throws IllegalArgumentException if contact method not found
     */
    ContactMethod markVerified(UUID contactMethodId);

    /**
     * Find contact methods by user ID.
     *
     * @param userId the user ID
     * @return list of contact methods
     */
    List<ContactMethod> findByUserId(UUID userId);

    /**
     * Find contact method by user ID and type.
     *
     * @param userId the user ID
     * @param type the contact type
     * @return the contact method if found
     */
    Optional<ContactMethod> findByUserIdAndType(UUID userId, ContactType type);

    /**
     * Delete contact method.
     *
     * @param contactMethodId the contact method ID
     * @throws IllegalArgumentException if contact method not found
     */
    void delete(UUID contactMethodId);
}
