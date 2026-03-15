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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcApiKeyCredentialRepositoryTest extends AbstractApiKeyTest {

    @Autowired
    private JdbcApiKeyCredentialRepository repository;

    @Test
    void savesAndFindsByKeyHash() {
        ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "My Key", "abc123hash");
        repository.save(credential);

        assertThat(repository.findByKeyHash("abc123hash"))
                .isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.id()).isEqualTo(credential.id());
                    assertThat(found.name()).isEqualTo("My Key");
                    assertThat(found.keyHash()).isEqualTo("abc123hash");
                });
    }

    @Test
    void returnsEmptyWhenHashNotFound() {
        assertThat(repository.findByKeyHash("nonexistent")).isEmpty();
    }

    @Test
    void deleteByIdRemovesCredential() {
        ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "Delete Me", "hash-to-delete");
        repository.save(credential);

        repository.deleteById(credential.id());

        assertThat(repository.findByKeyHash("hash-to-delete")).isEmpty();
    }

    @Test
    void saveUpdatesKeyHashOnConflictById() {
        UUID id = UUID.randomUUID();
        ApiKeyCredential first = new ApiKeyCredential(id, "Original Name", "original-hash");
        ApiKeyCredential second = new ApiKeyCredential(id, "Updated Name", "updated-hash");

        repository.save(first);
        repository.save(second);

        assertThat(repository.findByKeyHash("updated-hash"))
                .isPresent()
                .hasValueSatisfying(found -> assertThat(found.name()).isEqualTo("Updated Name"));
        assertThat(repository.findByKeyHash("original-hash")).isEmpty();
    }
}
