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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.servlet.http.HttpSessionEvent;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoSessions;
import org.springframework.mock.web.MockHttpSession;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSessionEndListenerTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  private final WhoSessions sessions =
      InMemorySessionRecords.sessions(Clock.fixed(NOW, ZoneOffset.UTC), WhoEventPublisher.NOOP);
  private final WhoSessionEndListener listener = new WhoSessionEndListener(sessions);

  @Test
  void a_destroyed_session_closes_its_row() {
    UUID identityId = UUID.randomUUID();
    UUID credentialId = UUID.randomUUID();
    SessionRecord row = sessions.start(identityId, credentialId, SignInMethod.PASSKEY, null);
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identityId, credentialId, NOW, SignInMethod.PASSKEY, row.id()));

    listener.sessionDestroyed(new HttpSessionEvent(session));

    assertThat(sessions.findLive(row.id())).isEmpty();
  }

  @Test
  void a_destroyed_session_without_who_does_nothing() {
    SessionRecord row =
        sessions.start(UUID.randomUUID(), UUID.randomUUID(), SignInMethod.PASSKEY, null);
    MockHttpSession plain = new MockHttpSession();
    MockHttpSession noRow = new MockHttpSession();
    noRow.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(UUID.randomUUID(), UUID.randomUUID(), NOW));

    listener.sessionDestroyed(new HttpSessionEvent(plain));
    listener.sessionDestroyed(new HttpSessionEvent(noRow));

    assertThat(sessions.findLive(row.id())).isPresent();
  }
}
