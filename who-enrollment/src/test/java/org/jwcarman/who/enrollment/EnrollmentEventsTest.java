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
package org.jwcarman.who.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.springframework.beans.factory.annotation.Autowired;

/** The events enrollment publishes: token creation and revocation, and a credential enrolled. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EnrollmentEventsTest extends AbstractEnrollmentTest {

  private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @Autowired private EnrollmentTokenRepository tokenRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  private final List<WhoEvent> events = new ArrayList<>();
  private WhoEnrollmentService service;

  @BeforeEach
  void build_service_with_a_recording_publisher() {
    service = serviceAt(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private WhoEnrollmentService serviceAt(Clock clock) {
    return new WhoEnrollmentService(
        tokenRepository,
        identityRepository,
        credentialIdentityRepository,
        Duration.ofHours(24),
        events::add,
        clock);
  }

  private Identity savedIdentity() {
    return identityRepository.save(Identity.create());
  }

  @Nested
  class Tokens {

    @Test
    void create_token_publishes_created_and_revokes_the_pending_one() {
      Identity identity = savedIdentity();
      EnrollmentToken first = service.createToken(identity);
      events.clear();

      EnrollmentToken second = service.createToken(identity);

      assertThat(events)
          .containsExactly(
              new EnrollmentTokenRevoked(NOW, identity.id(), first.id()),
              new EnrollmentTokenCreated(NOW, identity.id(), second.id()));
    }

    @Test
    void create_token_for_an_identity_without_a_pending_token_publishes_only_created() {
      Identity identity = savedIdentity();

      EnrollmentToken token = service.createToken(identity);

      assertThat(events)
          .containsExactly(new EnrollmentTokenCreated(NOW, identity.id(), token.id()));
    }

    @Test
    void create_token_stamps_the_token_from_the_clock() {
      Identity identity = savedIdentity();

      EnrollmentToken token = service.createToken(identity);

      assertThat(token.createdAt()).isEqualTo(NOW);
      assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void revoke_token_publishes_revoked() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      events.clear();

      service.revokeToken(token);

      assertThat(events)
          .containsExactly(new EnrollmentTokenRevoked(NOW, identity.id(), token.id()));
    }

    @Test
    void revoking_a_token_that_is_not_pending_publishes_nothing() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      service.revokeToken(token);
      events.clear();

      service.revokeToken(token);

      assertThat(events).isEmpty();
    }

    @Test
    void no_event_carries_the_token_value() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      service.createToken(identity);
      service.revokeToken(token);

      assertThat(events).isNotEmpty().noneMatch(event -> event.toString().contains(token.value()));
    }
  }

  @Nested
  class Enrolling {

    @Test
    void enroll_publishes_credential_added_with_the_credentials_kind() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      events.clear();
      UUID credentialId = UUID.randomUUID();

      service.enroll(token.value(), new LabelledCredential(credentialId));

      assertThat(events)
          .containsExactly(
              new CredentialAdded(
                  NOW, identity.id(), credentialId, CredentialKind.PASSKEY, "Work laptop"));
    }

    @Test
    void enroll_publishes_a_null_label_for_a_credential_without_one() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      events.clear();
      UUID credentialId = UUID.randomUUID();

      service.enroll(token.value(), () -> credentialId);

      assertThat(events)
          .containsExactly(
              new CredentialAdded(NOW, identity.id(), credentialId, CredentialKind.OTHER, null));
    }

    @Test
    void enroll_uses_the_clock_for_expiry() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      WhoEnrollmentService later =
          serviceAt(Clock.fixed(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC));

      String value = token.value();

      assertThatThrownBy(() -> later.enroll(value, UUID::randomUUID))
          .isInstanceOf(EnrollmentTokenExpiredException.class);
    }

    @Test
    void a_failed_enrollment_publishes_nothing() {
      Identity identity = savedIdentity();
      EnrollmentToken token = service.createToken(identity);
      events.clear();
      UUID credentialId = UUID.randomUUID();
      service.enroll(token.value(), () -> credentialId);
      events.clear();
      String value = token.value();

      assertThatThrownBy(() -> service.enroll(value, () -> credentialId))
          .isInstanceOf(EnrollmentTokenNotPendingException.class);

      assertThat(events).isEmpty();
    }
  }

  private record LabelledCredential(UUID id) implements Credential {

    @Override
    public CredentialKind kind() {
      return CredentialKind.PASSKEY;
    }

    @Override
    public Optional<String> label() {
      return Optional.of("Work laptop");
    }
  }
}
