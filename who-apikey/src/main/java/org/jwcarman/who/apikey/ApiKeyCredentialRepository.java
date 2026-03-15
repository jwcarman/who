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
package org.jwcarman.who.apikey;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for persisting and retrieving {@link ApiKeyCredential} records.
 */
public interface ApiKeyCredentialRepository {

    /**
     * Finds a credential by its SHA-256 key hash.
     *
     * @param keyHash the SHA-256 hex-encoded hash of the raw API key
     * @return the matching credential, or empty if not found
     */
    Optional<ApiKeyCredential> findByKeyHash(String keyHash);

    /**
     * Persists a credential. If a record with the same {@code id} already exists it is updated
     * with the new key hash.
     *
     * @param credential the credential to save
     * @return the saved credential
     */
    ApiKeyCredential save(ApiKeyCredential credential);

    /**
     * Deletes the credential with the given id.
     *
     * @param id the credential UUID
     */
    void deleteById(UUID id);
}
