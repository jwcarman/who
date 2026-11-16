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
package org.jwcarman.who.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemorySessionRecordRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  private final InMemorySessionRecordRepository repository = new InMemorySessionRecordRepository();
  private final UUID identityId = UUID.randomUUID();

  private SessionRecord row(UUID identity, Instant signedInAt, Instant lastSeenAt) {
    SessionRecord row =
        new SessionRecord(
            UUID.randomUUID(),
            identity,
            UUID.randomUUID(),
            SignInMethod.PASSKEY,
            signedInAt,
            lastSeenAt,
            null,
            null);
    repository.insert(row);
    return row;
  }

  @Test
  void finds_a_row_by_id() {
    SessionRecord row = row(identityId, NOW, NOW);

    assertThat(repository.findById(row.id())).contains(row);
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void lists_live_rows_of_one_identity_newest_first() {
    SessionRecord older = row(identityId, NOW.minusSeconds(60), NOW);
    SessionRecord newer = row(identityId, NOW, NOW);
    row(identityId, NOW, NOW.minusSeconds(3600)); // idle
    SessionRecord ended = row(identityId, NOW, NOW);
    repository.end(ended.id(), NOW);
    row(UUID.randomUUID(), NOW, NOW); // someone else's

    assertThat(repository.findLiveByIdentity(identityId, NOW.minusSeconds(60)))
        .extracting(SessionRecord::id)
        .containsExactly(newer.id(), older.id());
  }

  @Test
  void touches_only_a_live_row_not_seen_since() {
    SessionRecord row = row(identityId, NOW, NOW.minusSeconds(600));

    assertThat(repository.touch(row.id(), NOW, NOW.minusSeconds(300))).isTrue();
    assertThat(repository.findById(row.id()).orElseThrow().lastSeenAt()).isEqualTo(NOW);
    assertThat(repository.touch(row.id(), NOW, NOW.minusSeconds(300))).isFalse();
    assertThat(repository.touch(UUID.randomUUID(), NOW, NOW)).isFalse();
    repository.end(row.id(), NOW);
    assertThat(repository.touch(row.id(), NOW.plusSeconds(900), NOW.plusSeconds(600))).isFalse();
  }

  @Test
  void ends_a_row_once() {
    SessionRecord row = row(identityId, NOW, NOW);

    assertThat(repository.end(row.id(), NOW)).isTrue();
    assertThat(repository.findById(row.id()).orElseThrow().endedAt()).isEqualTo(NOW);
    assertThat(repository.end(row.id(), NOW)).isFalse();
    assertThat(repository.end(UUID.randomUUID(), NOW)).isFalse();
  }

  @Test
  void deletes_dead_rows_in_batches() {
    SessionRecord live = row(identityId, NOW, NOW);
    row(identityId, NOW, NOW.minusSeconds(3600));
    row(identityId, NOW, NOW.minusSeconds(3600));
    repository.end(row(identityId, NOW, NOW).id(), NOW);

    assertThat(repository.deleteDead(NOW.minusSeconds(60), 2)).isEqualTo(2);
    assertThat(repository.deleteDead(NOW.minusSeconds(60), 2)).isEqualTo(1);
    assertThat(repository.deleteDead(NOW.minusSeconds(60), 2)).isZero();
    assertThat(repository.findById(live.id())).isPresent();
  }
}
