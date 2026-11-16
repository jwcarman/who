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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.EndedSessionsPurged;
import org.jwcarman.who.core.event.SessionEndReason;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.SessionRecordRepository;
import org.mockito.ArgumentCaptor;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSessionsTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Duration IDLE = Duration.ofMinutes(30);
  private static final Duration SEEN = Duration.ofMinutes(5);

  private final SessionRecordRepository repository = mock(SessionRecordRepository.class);
  private final List<WhoEvent> events = new ArrayList<>();
  private final UUID identityId = UUID.randomUUID();

  private WhoSessions sessions(boolean autoPurge) {
    return new WhoSessions(
        repository, events::add, Clock.fixed(NOW, ZoneOffset.UTC), IDLE, SEEN, 100, autoPurge);
  }

  private final WhoSessions sessions = sessions(false);

  private SessionRecord record(Instant lastSeenAt, Instant endedAt) {
    return new SessionRecord(
        UUID.randomUUID(),
        identityId,
        UUID.randomUUID(),
        SignInMethod.PASSKEY,
        NOW.minus(Duration.ofHours(1)),
        lastSeenAt,
        null,
        endedAt);
  }

  private SessionRecord stored(SessionRecord session) {
    when(repository.findById(session.id())).thenReturn(Optional.of(session));
    return session;
  }

  private SessionRecord inserted() {
    ArgumentCaptor<SessionRecord> captor = ArgumentCaptor.forClass(SessionRecord.class);
    verify(repository).insert(captor.capture());
    return captor.getValue();
  }

  @Test
  void start_records_a_live_session_now() {
    UUID credentialId = UUID.randomUUID();

    SessionRecord started =
        sessions.start(identityId, credentialId, SignInMethod.EMAIL_LINK, "Mozilla/5.0");

    assertThat(inserted()).isEqualTo(started);
    assertThat(started.identityId()).isEqualTo(identityId);
    assertThat(started.credentialId()).isEqualTo(credentialId);
    assertThat(started.signInMethod()).isEqualTo(SignInMethod.EMAIL_LINK);
    assertThat(started.signedInAt()).isEqualTo(NOW);
    assertThat(started.lastSeenAt()).isEqualTo(NOW);
    assertThat(started.userAgent()).isEqualTo("Mozilla/5.0");
    assertThat(started.endedAt()).isNull();
  }

  @Test
  void start_can_record_an_earlier_sign_in() {
    Instant earlier = NOW.minus(Duration.ofMinutes(20));

    SessionRecord started =
        sessions.start(identityId, UUID.randomUUID(), SignInMethod.EMAIL_CODE, null, earlier);

    assertThat(started.signedInAt()).isEqualTo(earlier);
    assertThat(started.lastSeenAt()).isEqualTo(NOW);
  }

  @Test
  void start_truncates_a_long_user_agent() {
    SessionRecord started =
        sessions.start(identityId, UUID.randomUUID(), SignInMethod.PASSKEY, "x".repeat(600));

    assertThat(started.userAgent()).hasSize(512);
  }

  @Test
  void start_keeps_no_blank_user_agent() {
    assertThat(
            sessions.start(identityId, UUID.randomUUID(), SignInMethod.PASSKEY, "  ").userAgent())
        .isNull();
  }

  @Test
  void start_purges_one_batch_when_auto_purge_is_on() {
    sessions(true).start(identityId, UUID.randomUUID(), SignInMethod.PASSKEY, null);

    verify(repository).deleteDead(NOW.minus(IDLE), 100);
  }

  @Test
  void start_does_not_purge_when_auto_purge_is_off() {
    sessions.start(identityId, UUID.randomUUID(), SignInMethod.PASSKEY, null);

    verify(repository, never()).deleteDead(any(), anyInt());
  }

  @Test
  void an_idle_session_is_not_live() {
    SessionRecord idle = stored(record(NOW.minus(IDLE).minusSeconds(1), null));
    SessionRecord recent = stored(record(NOW.minus(IDLE).plusSeconds(1), null));

    assertThat(sessions.findLive(idle.id())).isEmpty();
    assertThat(sessions.findLive(recent.id())).contains(recent);
  }

  @Test
  void an_ended_or_unknown_session_is_not_live() {
    SessionRecord ended = stored(record(NOW, NOW));

    assertThat(sessions.findLive(ended.id())).isEmpty();
    assertThat(sessions.findLive(UUID.randomUUID())).isEmpty();
  }

  @Test
  void seen_does_not_write_within_the_interval() {
    sessions.seen(record(NOW.minus(SEEN).plusSeconds(1), null));

    verify(repository, never()).touch(any(), any(), any());
  }

  @Test
  void seen_writes_after_the_interval() {
    SessionRecord session = record(NOW.minus(SEEN).minusSeconds(1), null);

    sessions.seen(session);

    verify(repository).touch(session.id(), NOW, NOW.minus(SEEN));
  }

  @Test
  void list_reads_live_sessions() {
    List<SessionRecord> live = List.of(record(NOW, null));
    when(repository.findLiveByIdentity(identityId, NOW.minus(IDLE))).thenReturn(live);

    assertThat(sessions.list(identityId)).isEqualTo(live);
  }

  @Test
  void end_publishes_revoked() {
    SessionRecord session = stored(record(NOW, null));
    when(repository.end(session.id(), NOW)).thenReturn(true);

    assertThat(sessions.end(session.id())).isTrue();

    assertThat(events)
        .containsExactly(
            new SessionEnded(NOW, identityId, session.credentialId(), SessionEndReason.REVOKED));
  }

  @Test
  void ending_a_dead_session_does_nothing() {
    SessionRecord ended = stored(record(NOW, NOW));
    SessionRecord idle = stored(record(NOW.minus(Duration.ofHours(1)), null));

    assertThat(sessions.end(ended.id())).isFalse();
    assertThat(sessions.end(idle.id())).isFalse();
    assertThat(sessions.end(UUID.randomUUID())).isFalse();
    verify(repository, never()).end(any(), any());
    assertThat(events).isEmpty();
  }

  @Test
  void a_session_ended_by_a_concurrent_call_publishes_nothing() {
    SessionRecord session = stored(record(NOW, null));
    when(repository.end(session.id(), NOW)).thenReturn(false);

    assertThat(sessions.end(session.id())).isFalse();
    assertThat(events).isEmpty();
  }

  @Test
  void end_all_ends_every_live_session() {
    List<SessionRecord> live = List.of(record(NOW, null), record(NOW, null));
    when(repository.findLiveByIdentity(identityId, NOW.minus(IDLE))).thenReturn(live);
    live.forEach(s -> when(repository.end(s.id(), NOW)).thenReturn(true));

    assertThat(sessions.endAll(identityId)).isEqualTo(2);
    assertThat(events).hasSize(2);
  }

  @Test
  void end_all_except_keeps_one() {
    SessionRecord keep = record(NOW, null);
    List<SessionRecord> live = List.of(record(NOW, null), keep, record(NOW, null));
    when(repository.findLiveByIdentity(identityId, NOW.minus(IDLE))).thenReturn(live);
    live.forEach(s -> when(repository.end(s.id(), NOW)).thenReturn(true));

    assertThat(sessions.endAllExcept(identityId, keep.id())).isEqualTo(2);

    verify(repository, never()).end(eq(keep.id()), any());
    assertThat(events).hasSize(2);
  }

  @Test
  void close_publishes_nothing() {
    UUID id = UUID.randomUUID();

    sessions.close(id);

    verify(repository).end(id, NOW);
    assertThat(events).isEmpty();
  }

  @Test
  void purge_reports_the_count() {
    when(repository.deleteDead(NOW.minus(IDLE), 100)).thenReturn(7, 0);

    assertThat(sessions.purgeEndedSessions()).isEqualTo(7);
    assertThat(sessions.purgeEndedSessions()).isZero();

    assertThat(events).containsExactly(new EndedSessionsPurged(NOW, 7));
  }

  @Test
  void settings_must_be_positive() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new WhoSessions(repository, events::add, clock, Duration.ZERO, SEEN, 100, true));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WhoSessions(
                    repository, events::add, clock, IDLE, Duration.ofSeconds(-1), 100, true));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WhoSessions(repository, events::add, clock, IDLE, SEEN, 0, true));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 | Chrome on Windows
          Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15 | Safari on macOS
          Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1 | Safari on iOS
          Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0 | Firefox on Linux
          Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0 | Edge on Windows
          Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 | Chrome on Android
          Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 OPR/115.0.0.0 | Opera on Windows
          Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 | Chrome on ChromeOS
          curl/8.4.0 | Unknown browser on unknown system
          """)
  void device_label_reads_common_browsers(String userAgent, String label) {
    assertThat(WhoSessions.deviceLabel(userAgent)).isEqualTo(label);
  }

  @Test
  void device_label_without_a_user_agent_is_unknown() {
    assertThat(WhoSessions.deviceLabel(null)).isEqualTo("Unknown browser");
    assertThat(WhoSessions.deviceLabel(" ")).isEqualTo("Unknown browser");
  }
}
