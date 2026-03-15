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

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest extends AbstractApiKeyTest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiKeyCredentialRepository apiKeyCredentialRepository;

    @Autowired
    private CredentialIdentityRepository credentialIdentityRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Test
    void createReturnsKeyWithWhoPrefix() {
        UUID identityId = createActiveIdentity();
        String rawKey = apiKeyService.create(identityId);

        assertThat(rawKey).startsWith("who_");
        assertThat(rawKey).hasSize(4 + 64); // "who_" + 64 hex chars
    }

    @Test
    void createStoresHashNotRawKey() {
        UUID identityId = createActiveIdentity();
        String rawKey = apiKeyService.create(identityId);

        // The raw key must NOT be stored as-is
        assertThat(apiKeyCredentialRepository.findByKeyHash(rawKey)).isEmpty();

        // The SHA-256 hash MUST be stored
        String expectedHash = ApiKeyService.sha256Hex(rawKey);
        assertThat(apiKeyCredentialRepository.findByKeyHash(expectedHash)).isPresent();
    }

    @Test
    void createLinksCredentialToIdentity() {
        UUID identityId = createActiveIdentity();
        String rawKey = apiKeyService.create(identityId);

        String hash = ApiKeyService.sha256Hex(rawKey);
        ApiKeyCredential credential = apiKeyCredentialRepository.findByKeyHash(hash).orElseThrow();

        assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
                .isPresent()
                .hasValue(identityId);
    }

    @Test
    void twoCreatesForSameIdentityProduceDifferentKeys() {
        UUID identityId = createActiveIdentity();
        String key1 = apiKeyService.create(identityId);
        String key2 = apiKeyService.create(identityId);

        assertThat(key1).isNotEqualTo(key2);

        // Both hashes must be stored
        assertThat(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(key1))).isPresent();
        assertThat(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(key2))).isPresent();
    }

    private UUID createActiveIdentity() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        identityRepository.save(identity);
        return identity.id();
    }
}
