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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcApiKeyCredentialRepositoryTest extends AbstractApiKeyTest {

  // Postgres keeps microseconds
  private static final Instant CREATED = Instant.now().truncatedTo(ChronoUnit.MICROS);
  private static final Instant EXPIRES = CREATED.plus(Duration.ofDays(30));

  @Autowired private JdbcApiKeyCredentialRepository repository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private JdbcClient jdbcClient;

  private static ApiKeyCredential key(UUID id, String name, String keyHash) {
    return new ApiKeyCredential(id, name, keyHash, Set.of("task.read"), CREATED, EXPIRES, null);
  }

  private static ApiKeyCredential key(UUID id, Set<String> scopes, Instant lastUsedAt) {
    return new ApiKeyCredential(id, "Scoped", "hash-" + id, scopes, CREATED, EXPIRES, lastUsedAt);
  }

  @Test
  void saves_and_reads_back_scopes_and_times() {
    Instant used = CREATED.plusSeconds(60);
    ApiKeyCredential credential = repository.save(key(UUID.randomUUID(), Set.of("a", "b"), used));

    assertThat(repository.findById(credential.id())).contains(credential);
    assertThat(repository.findByKeyHash(credential.keyHash())).contains(credential);
  }

  @Test
  void saving_again_replaces_the_scopes() {
    UUID id = UUID.randomUUID();
    repository.save(key(id, Set.of("a", "b"), null));

    repository.save(key(id, Set.of("c"), null));

    assertThat(repository.findById(id))
        .hasValueSatisfying(found -> assertThat(found.scopes()).containsExactly("c"));
  }

  @Test
  void deleting_a_key_deletes_its_scopes() {
    ApiKeyCredential credential = repository.save(key(UUID.randomUUID(), Set.of("a"), null));

    repository.deleteById(credential.id());

    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_api_key_scope WHERE credential_id = :id")
                .param("id", credential.id())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void touch_writes_when_never_used() {
    ApiKeyCredential credential = repository.save(key(UUID.randomUUID(), Set.of("a"), null));
    Instant now = CREATED.plusSeconds(10);

    assertThat(repository.touch(credential.id(), now, now.minusSeconds(300))).isTrue();
    assertThat(repository.findById(credential.id()))
        .hasValueSatisfying(found -> assertThat(found.lastUsedAt()).isEqualTo(now));
  }

  @Test
  void touch_skips_within_the_interval() {
    Instant used = CREATED.plusSeconds(10);
    ApiKeyCredential credential = repository.save(key(UUID.randomUUID(), Set.of("a"), used));
    Instant now = used.plusSeconds(60);

    assertThat(repository.touch(credential.id(), now, now.minusSeconds(300))).isFalse();
    assertThat(repository.findById(credential.id()))
        .hasValueSatisfying(found -> assertThat(found.lastUsedAt()).isEqualTo(used));
  }

  @Test
  void touch_writes_after_it() {
    Instant used = CREATED.plusSeconds(10);
    ApiKeyCredential credential = repository.save(key(UUID.randomUUID(), Set.of("a"), used));
    Instant now = used.plusSeconds(301);

    assertThat(repository.touch(credential.id(), now, now.minusSeconds(300))).isTrue();
    assertThat(repository.findById(credential.id()))
        .hasValueSatisfying(found -> assertThat(found.lastUsedAt()).isEqualTo(now));
  }

  @Test
  void touch_of_a_gone_key_writes_nothing() {
    Instant now = CREATED.plusSeconds(10);

    assertThat(repository.touch(UUID.randomUUID(), now, now.minusSeconds(300))).isFalse();
  }

  @Test
  void savesAndFindsByKeyHash() {
    ApiKeyCredential credential = key(UUID.randomUUID(), "My Key", "abc123hash");
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
    ApiKeyCredential credential = key(UUID.randomUUID(), "Delete Me", "hash-to-delete");
    repository.save(credential);

    assertThat(repository.deleteById(credential.id())).isTrue();

    assertThat(repository.findByKeyHash("hash-to-delete")).isEmpty();
    assertThat(repository.deleteById(credential.id())).isFalse();
  }

  @Test
  void saveUpdatesKeyHashOnConflictById() {
    UUID id = UUID.randomUUID();
    ApiKeyCredential first = key(id, "Original Name", "original-hash");
    ApiKeyCredential second = key(id, "Updated Name", "updated-hash");

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
        repository.save(key(UUID.randomUUID(), "Remove Me", "hash-to-remove"));

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
    ApiKeyCredential linked = repository.save(key(UUID.randomUUID(), "Linked", "linked-hash"));
    credentialIdentityRepository.link(linked.id(), identity.id());
    repository.save(key(UUID.randomUUID(), "Orphan", "orphan-hash"));

    assertThat(repository.removeUnlinked()).isOne();
    assertThat(repository.findByKeyHash("orphan-hash")).isEmpty();
    assertThat(repository.findByKeyHash("linked-hash")).contains(linked);
  }

  @Test
  void findByIdFindsTheCredential() {
    ApiKeyCredential credential =
        repository.save(key(UUID.randomUUID(), "Find Me", "hash-to-find"));

    assertThat(repository.findById(credential.id())).contains(credential);
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findDescribesTheCredentialAsAnApiKeyLabelledWithItsName() {
    ApiKeyCredential credential =
        repository.save(key(UUID.randomUUID(), "Laptop", "hash-to-describe"));

    assertThat(repository.find(credential.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(credential.id());
              assertThat(found.kind()).isEqualTo(CredentialKind.API_KEY);
              assertThat(found.label()).contains("Laptop");
            });
    assertThat(repository.find(UUID.randomUUID())).isEmpty();
  }
}
