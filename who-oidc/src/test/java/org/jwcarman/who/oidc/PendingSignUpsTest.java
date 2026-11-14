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
package org.jwcarman.who.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.signup.SignUpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PendingSignUpsTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID BOB = UUID.randomUUID();

  private final PendingSignUps pendingSignUps = new PendingSignUps();
  private final MockHttpServletRequest request = new MockHttpServletRequest();

  private static PendingSignUp pending(UUID createdFor) {
    return new PendingSignUp(
        "google",
        "https://accounts.google.com",
        "alice-sub",
        "alice@example.com",
        "alice@example.com",
        Map.of("hd", "example.com"),
        createdFor,
        NOW.plusSeconds(600));
  }

  @Test
  void a_live_one_is_current() {
    pendingSignUps.save(request, pending(null));

    assertThat(pendingSignUps.current(request, null, NOW)).contains(pending(null));
  }

  @Test
  void expired_is_dropped() {
    pendingSignUps.save(request, pending(null));

    assertThat(pendingSignUps.current(request, null, NOW.plusSeconds(600))).isEmpty();
    assertThat(request.getSession().getAttribute(PendingSignUps.ATTRIBUTE)).isNull();
  }

  @Test
  void another_identity_drops_it() {
    pendingSignUps.save(request, pending(ALICE));

    assertThat(pendingSignUps.current(request, BOB, NOW)).isEmpty();
    assertThat(pendingSignUps.current(request, ALICE, NOW)).isEmpty();
  }

  @Test
  void signed_out_created_is_dropped_once_someone_signs_in() {
    pendingSignUps.save(request, pending(null));

    assertThat(pendingSignUps.current(request, ALICE, NOW)).isEmpty();
    assertThat(pendingSignUps.current(request, null, NOW)).isEmpty();
  }

  @Test
  void signed_in_created_is_dropped_once_signed_out() {
    pendingSignUps.save(request, pending(ALICE));

    assertThat(pendingSignUps.current(request, null, NOW)).isEmpty();
  }

  @Test
  void consume_removes_it() {
    pendingSignUps.save(request, pending(ALICE));

    pendingSignUps.consume(request);

    assertThat(pendingSignUps.current(request, ALICE, NOW)).isEmpty();
  }

  @Test
  void no_session_means_none_and_creates_no_session() {
    assertThat(pendingSignUps.current(request, null, NOW)).isEmpty();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void to_request_carries_the_account() {
    SignUpRequest signUp = pending(null).toRequest();

    assertThat(signUp.method()).isEqualTo(SignInMethod.OIDC);
    assertThat(signUp.registrationId()).isEqualTo("google");
    assertThat(signUp.issuer()).isEqualTo("https://accounts.google.com");
    assertThat(signUp.subject()).isEqualTo("alice-sub");
    assertThat(signUp.verifiedEmail()).contains("alice@example.com");
    assertThat(signUp.claims()).containsEntry("hd", "example.com");
  }

  @Test
  void it_survives_java_serialization() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(pending(ALICE));
    }
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      assertThat(in.readObject()).isEqualTo(pending(ALICE));
    }
  }
}
