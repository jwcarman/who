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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcApiKeyCredentialRepositoryTest extends AbstractApiKeyTest {

  @Autowired private JdbcApiKeyCredentialRepository repository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  @Test
  void savesAndFindsByKeyHash() {
    ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "My Key", "abc123hash");
    repository.save(credential);

    assertThat(repository.findByKeyHash("abc123hash"))
        .isPresent()
        .hasValueSatisfying(
            found -> {
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
    ApiKeyCredential credential =
        new ApiKeyCredential(UUID.randomUUID(), "Delete Me", "hash-to-delete");
    repository.save(credential);

    assertThat(repository.deleteById(credential.id())).isTrue();

    assertThat(repository.findByKeyHash("hash-to-delete")).isEmpty();
    assertThat(repository.deleteById(credential.id())).isFalse();
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

  @Test
  void removeDeletesTheCredentialItOwns() {
    ApiKeyCredential credential =
        repository.save(new ApiKeyCredential(UUID.randomUUID(), "Remove Me", "hash-to-remove"));

    assertThat(repository.remove(credential.id())).isTrue();
    assertThat(repository.findByKeyHash("hash-to-remove")).isEmpty();
  }

  @Test
  void removeOfAnotherModulesCredentialDeletesNothing() {
    assertThat(repository.remove(UUID.randomUUID())).isFalse();
  }

  @Test
  void removeUnlinkedDeletesOnlyUnlinkedCredentials() {
    repository.removeUnlinked();
    Identity identity = identityRepository.save(Identity.create());
    ApiKeyCredential linked =
        repository.save(new ApiKeyCredential(UUID.randomUUID(), "Linked", "linked-hash"));
    credentialIdentityRepository.link(linked.id(), identity.id());
    repository.save(new ApiKeyCredential(UUID.randomUUID(), "Orphan", "orphan-hash"));

    assertThat(repository.removeUnlinked()).isOne();
    assertThat(repository.findByKeyHash("orphan-hash")).isEmpty();
    assertThat(repository.findByKeyHash("linked-hash")).contains(linked);
  }
}
