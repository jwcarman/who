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

import org.jwcarman.who.core.domain.Identity;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for {@link Identity} persistence.
 */
public interface IdentityRepository {

    /**
     * Finds an identity by its UUID.
     *
     * @param id the identity UUID
     * @return the identity, or empty if not found
     */
    Optional<Identity> findById(UUID id);

    /**
     * Persists the given identity, inserting or updating as necessary.
     *
     * @param identity the identity to save
     * @return the saved identity
     */
    Identity save(Identity identity);

    /**
     * Returns whether an identity with the given UUID exists.
     *
     * @param id the identity UUID
     * @return true if an identity exists with that UUID
     */
    boolean existsById(UUID id);

    /**
     * Deletes the identity with the given UUID.
     *
     * @param id the identity UUID
     */
    void deleteById(UUID id);
}
