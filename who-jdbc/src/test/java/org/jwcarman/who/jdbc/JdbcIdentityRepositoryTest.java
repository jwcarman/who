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
package org.jwcarman.who.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcIdentityRepositoryTest extends AbstractJdbcTest {

  @Autowired private JdbcIdentityRepository repository;

  @Autowired private JdbcClient jdbcClient;

  @Test
  void timestampColumnsAreTimestamptz() {
    List<String> types =
        jdbcClient
            .sql(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'who_identity' AND column_name IN ('created_at', 'updated_at')")
            .query(String.class)
            .list();

    assertThat(types).hasSize(2).containsOnly("timestamp with time zone");
  }

  @Test
  void instantsRoundTripRegardlessOfJvmZone() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    try {
      Instant instant = Instant.parse("2026-01-15T12:34:56.123456Z");
      Identity identity = new Identity(UUID.randomUUID(), IdentityStatus.ACTIVE, instant, instant);
      repository.save(identity);

      Identity found = repository.findById(identity.id()).orElseThrow();

      assertThat(found.createdAt()).isEqualTo(instant);
      assertThat(found.updatedAt()).isEqualTo(instant);
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void savesNewIdentityAndRetrievesItById() {
    Identity identity = Identity.create();
    repository.save(identity);

    Optional<Identity> found = repository.findById(identity.id());

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(identity.id());
    assertThat(found.get().status()).isEqualTo(IdentityStatus.ACTIVE);
  }

  @Test
  void upsertUpdatesStatusOnConflict() {
    Identity identity = Identity.create();
    repository.save(identity);

    Identity suspended = identity.withStatus(IdentityStatus.SUSPENDED);
    repository.save(suspended);

    Optional<Identity> found = repository.findById(identity.id());
    assertThat(found).isPresent();
    assertThat(found.get().status()).isEqualTo(IdentityStatus.SUSPENDED);
  }

  @Test
  void existsByIdReturnsTrueWhenIdentityExists() {
    Identity identity = Identity.create();
    repository.save(identity);

    assertThat(repository.existsById(identity.id())).isTrue();
  }

  @Test
  void existsByIdReturnsFalseWhenIdentityAbsent() {
    assertThat(repository.existsById(UUID.randomUUID())).isFalse();
  }

  @Test
  void deleteByIdRemovesIdentity() {
    Identity identity = Identity.create();
    repository.save(identity);

    repository.deleteById(identity.id());

    assertThat(repository.findById(identity.id())).isEmpty();
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void change_status_is_atomic_under_concurrency() throws Exception {
    Identity identity = Identity.create();
    repository.save(identity);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> change =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return repository.changeStatus(identity.id(), IdentityStatus.DISABLED);
          };

      List<Future<Boolean>> results = executor.invokeAll(List.of(change, change));

      List<Boolean> outcomes = new ArrayList<>();
      for (Future<Boolean> result : results) {
        outcomes.add(result.get());
      }
      assertThat(outcomes).containsExactlyInAnyOrder(true, false);
      assertThat(repository.findById(identity.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.DISABLED);
    } finally {
      executor.shutdownNow();
      repository.deleteById(identity.id());
    }
  }

  @Nested
  class Paging {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private Identity saved(IdentityStatus status, Instant createdAt, UUID id) {
      return repository.save(new Identity(id, status, createdAt, createdAt));
    }

    private Identity saved(IdentityStatus status, Instant createdAt) {
      return saved(status, createdAt, UUID.randomUUID());
    }

    @Test
    void filters_by_role_without_listing_its_holders() {
      // who-rbac's table, which only a role filter reads; rolled back with the test
      jdbcClient
          .sql(
              "CREATE TABLE IF NOT EXISTS who_identity_role"
                  + " (identity_id UUID NOT NULL, role_id UUID NOT NULL,"
                  + " PRIMARY KEY (identity_id, role_id))")
          .update();
      UUID editor = UUID.randomUUID();
      UUID other = UUID.randomUUID();
      Identity first = saved(IdentityStatus.ACTIVE, T0);
      Identity second = saved(IdentityStatus.DISABLED, T0.plusSeconds(1));
      Identity neither = saved(IdentityStatus.ACTIVE, T0.plusSeconds(2));
      for (UUID[] row :
          List.of(
              new UUID[] {first.id(), editor},
              new UUID[] {second.id(), editor},
              new UUID[] {neither.id(), other})) {
        jdbcClient
            .sql("INSERT INTO who_identity_role (identity_id, role_id) VALUES (:i, :r)")
            .param("i", row[0])
            .param("r", row[1])
            .update();
      }

      assertThat(
              repository.findPage(Optional.empty(), Optional.of(editor), Optional.empty(), 0, 10))
          .extracting(Identity::id)
          .containsExactly(first.id(), second.id());
      assertThat(repository.count(Optional.empty(), Optional.of(editor), Optional.empty()))
          .isEqualTo(2);
      assertThat(
              repository.findPage(
                  Optional.of(IdentityStatus.DISABLED),
                  Optional.of(editor),
                  Optional.empty(),
                  0,
                  10))
          .extracting(Identity::id)
          .containsExactly(second.id());
      assertThat(
              repository.count(
                  Optional.empty(), Optional.of(editor), Optional.of(Set.of(neither.id()))))
          .isZero();
      assertThat(
              repository.count(Optional.empty(), Optional.of(UUID.randomUUID()), Optional.empty()))
          .isZero();
    }

    @Test
    void pages_oldest_first_then_by_id() {
      Identity third = saved(IdentityStatus.ACTIVE, T0.plusSeconds(2));
      Identity tiedHigh =
          saved(IdentityStatus.ACTIVE, T0, UUID.fromString("ffffffff-0000-0000-0000-000000000000"));
      Identity tiedLow =
          saved(IdentityStatus.ACTIVE, T0, UUID.fromString("00000000-0000-0000-0000-00000000000f"));
      Optional<Set<UUID>> ids = Optional.of(Set.of(third.id(), tiedHigh.id(), tiedLow.id()));

      assertThat(repository.findPage(Optional.empty(), Optional.empty(), ids, 0, 2))
          .extracting(Identity::id)
          .containsExactly(tiedLow.id(), tiedHigh.id());
      assertThat(repository.findPage(Optional.empty(), Optional.empty(), ids, 1, 2))
          .extracting(Identity::id)
          .containsExactly(third.id());
      assertThat(repository.findPage(Optional.empty(), Optional.empty(), ids, 2, 2)).isEmpty();
      assertThat(repository.count(Optional.empty(), Optional.empty(), ids)).isEqualTo(3);
    }

    @Test
    void filters_by_status() {
      Identity active = saved(IdentityStatus.ACTIVE, T0);
      Identity disabled = saved(IdentityStatus.DISABLED, T0.plusSeconds(1));
      Optional<Set<UUID>> ids = Optional.of(Set.of(active.id(), disabled.id()));

      assertThat(
              repository.findPage(
                  Optional.of(IdentityStatus.DISABLED), Optional.empty(), ids, 0, 10))
          .extracting(Identity::id)
          .containsExactly(disabled.id());
      assertThat(repository.count(Optional.of(IdentityStatus.DISABLED), Optional.empty(), ids))
          .isEqualTo(1);
    }

    @Test
    void an_empty_id_set_matches_none() {
      saved(IdentityStatus.ACTIVE, T0);

      assertThat(
              repository.findPage(Optional.empty(), Optional.empty(), Optional.of(Set.of()), 0, 10))
          .isEmpty();
      assertThat(repository.count(Optional.empty(), Optional.empty(), Optional.of(Set.of())))
          .isZero();
    }

    @Test
    void a_negative_page_or_a_size_below_one_is_refused() {
      assertThatThrownBy(
              () ->
                  repository.findPage(Optional.empty(), Optional.empty(), Optional.empty(), -1, 10))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> repository.findPage(Optional.empty(), Optional.empty(), Optional.empty(), 0, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void without_filters_every_identity_counts() {
      long before = repository.count(Optional.empty(), Optional.empty(), Optional.empty());
      Identity identity = saved(IdentityStatus.SUSPENDED, T0);

      assertThat(repository.count(Optional.empty(), Optional.empty(), Optional.empty()))
          .isEqualTo(before + 1);
      assertThat(
              repository.findPage(
                  Optional.empty(), Optional.empty(), Optional.empty(), 0, (int) before + 1))
          .extracting(Identity::id)
          .contains(identity.id());
    }
  }
}
