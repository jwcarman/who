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
package org.jwcarman.who.spring.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.SessionRecordRepository;
import org.jwcarman.who.core.service.WhoSessions;

/** Sessions kept in memory, for this module's tests (who-test has the public one). */
final class InMemorySessionRecords implements SessionRecordRepository {

  private final Map<UUID, SessionRecord> rows = new ConcurrentHashMap<>();

  /** A {@code WhoSessions} over a fresh in-memory store: 30 minutes idle, 5 minutes last-seen. */
  static WhoSessions sessions(Clock clock, WhoEventPublisher events) {
    return new WhoSessions(
        new InMemorySessionRecords(),
        events,
        clock,
        Duration.ofMinutes(30),
        Duration.ofMinutes(5),
        100,
        false);
  }

  @Override
  public void insert(SessionRecord session) {
    rows.put(session.id(), session);
  }

  @Override
  public Optional<SessionRecord> findById(UUID id) {
    return Optional.ofNullable(rows.get(id));
  }

  @Override
  public List<SessionRecord> findLiveByIdentity(UUID identityId, Instant seenSince) {
    return rows.values().stream()
        .filter(s -> s.identityId().equals(identityId))
        .filter(s -> s.endedAt() == null && !s.lastSeenAt().isBefore(seenSince))
        .sorted(Comparator.comparing(SessionRecord::signedInAt).reversed())
        .toList();
  }

  @Override
  public boolean touch(UUID id, Instant now, Instant notSince) {
    SessionRecord s = rows.get(id);
    if (s == null || s.endedAt() != null || !s.lastSeenAt().isBefore(notSince)) {
      return false;
    }
    rows.put(id, with(s, now, null));
    return true;
  }

  @Override
  public boolean end(UUID id, Instant now) {
    SessionRecord s = rows.get(id);
    if (s == null || s.endedAt() != null) {
      return false;
    }
    rows.put(id, with(s, s.lastSeenAt(), now));
    return true;
  }

  @Override
  public int deleteDead(Instant seenBefore, int limit) {
    List<UUID> dead =
        rows.values().stream()
            .filter(s -> s.endedAt() != null || s.lastSeenAt().isBefore(seenBefore))
            .limit(limit)
            .map(SessionRecord::id)
            .toList();
    dead.forEach(rows::remove);
    return dead.size();
  }

  private static SessionRecord with(SessionRecord s, Instant lastSeenAt, Instant endedAt) {
    return new SessionRecord(
        s.id(),
        s.identityId(),
        s.credentialId(),
        s.signInMethod(),
        s.signedInAt(),
        lastSeenAt,
        s.userAgent(),
        endedAt);
  }
}
