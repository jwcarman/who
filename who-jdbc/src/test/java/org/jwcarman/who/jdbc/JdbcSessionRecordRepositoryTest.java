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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcSessionRecordRepositoryTest extends AbstractJdbcTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  @Autowired private JdbcSessionRecordRepository repository;
  @Autowired private JdbcIdentityRepository identities;
  @Autowired private JdbcClient jdbcClient;

  private UUID identity() {
    return identities.save(Identity.create()).id();
  }

  private SessionRecord session(UUID identityId, Instant signedInAt, Instant lastSeenAt) {
    SessionRecord session =
        new SessionRecord(
            UUID.randomUUID(),
            identityId,
            UUID.randomUUID(),
            SignInMethod.PASSKEY,
            signedInAt,
            lastSeenAt,
            "Mozilla/5.0",
            null);
    repository.insert(session);
    return session;
  }

  @Test
  void inserted_session_is_found_by_id() {
    SessionRecord session = session(identity(), NOW, NOW);

    assertThat(repository.findById(session.id())).contains(session);
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void a_session_without_a_user_agent_round_trips() {
    UUID identityId = identity();
    SessionRecord session =
        new SessionRecord(
            UUID.randomUUID(),
            identityId,
            UUID.randomUUID(),
            SignInMethod.EMAIL_CODE,
            NOW,
            NOW,
            null,
            null);
    repository.insert(session);

    assertThat(repository.findById(session.id())).contains(session);
  }

  @Test
  void live_sessions_are_newest_first_and_skip_ended_and_idle() {
    UUID identityId = identity();
    SessionRecord older = session(identityId, NOW.minusSeconds(600), NOW);
    SessionRecord newer = session(identityId, NOW.minusSeconds(60), NOW);
    SessionRecord ended = session(identityId, NOW, NOW);
    repository.end(ended.id(), NOW);
    session(identityId, NOW.minusSeconds(7200), NOW.minusSeconds(3600));
    session(identity(), NOW, NOW);

    assertThat(repository.findLiveByIdentity(identityId, NOW.minusSeconds(1800)))
        .extracting(SessionRecord::id)
        .containsExactly(newer.id(), older.id());
  }

  @Test
  void touch_writes_only_once_per_interval() {
    SessionRecord session = session(identity(), NOW, NOW.minus(Duration.ofMinutes(1)));

    assertThat(repository.touch(session.id(), NOW, NOW.minus(Duration.ofMinutes(5)))).isFalse();
    assertThat(repository.findById(session.id()).orElseThrow().lastSeenAt())
        .isEqualTo(NOW.minus(Duration.ofMinutes(1)));

    Instant later = NOW.plus(Duration.ofMinutes(6));
    assertThat(repository.touch(session.id(), later, NOW.plus(Duration.ofMinutes(1)))).isTrue();
    assertThat(repository.findById(session.id()).orElseThrow().lastSeenAt()).isEqualTo(later);
  }

  @Test
  void an_ended_session_is_not_touched() {
    SessionRecord session = session(identity(), NOW, NOW.minus(Duration.ofHours(1)));
    repository.end(session.id(), NOW);

    assertThat(repository.touch(session.id(), NOW, NOW)).isFalse();
  }

  @Test
  void ending_is_once() {
    SessionRecord session = session(identity(), NOW, NOW);

    assertThat(repository.end(session.id(), NOW)).isTrue();
    assertThat(repository.end(session.id(), NOW.plusSeconds(60))).isFalse();
    assertThat(repository.findById(session.id()).orElseThrow().endedAt()).isEqualTo(NOW);
  }

  @Test
  void delete_dead_takes_at_most_the_limit() {
    UUID identityId = identity();
    for (int i = 0; i < 5; i++) {
      repository.end(session(identityId, NOW, NOW).id(), NOW);
    }
    session(identityId, NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));
    SessionRecord live = session(identityId, NOW, NOW);
    SessionRecord alsoLive = session(identityId, NOW, NOW);
    Instant seenBefore = NOW.minus(Duration.ofMinutes(30));

    assertThat(repository.deleteDead(seenBefore, 4)).isEqualTo(4);
    assertThat(repository.deleteDead(seenBefore, 4)).isEqualTo(2);
    assertThat(repository.deleteDead(seenBefore, 4)).isZero();
    assertThat(repository.findById(live.id())).isPresent();
    assertThat(repository.findById(alsoLive.id())).isPresent();
  }

  @Test
  void deleting_the_identity_deletes_its_sessions() {
    UUID identityId = identity();
    SessionRecord session = session(identityId, NOW, NOW);

    jdbcClient.sql("DELETE FROM who_identity WHERE id = :id").param("id", identityId).update();

    assertThat(repository.findById(session.id())).isEmpty();
  }

  @Test
  void ended_rows_are_found_by_an_index_so_the_purge_need_not_scan() {
    // the purge looks for ended rows on every sign-in; without an index it reads the whole table
    assertThat(
            jdbcClient
                .sql(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'who_session'"
                        + " AND indexdef LIKE '%(ended_at)%'")
                .query(String.class)
                .list())
        .isNotEmpty();
  }
}
