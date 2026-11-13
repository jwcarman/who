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
package org.jwcarman.who.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.spi.Credential;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoEventsTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ID = UUID.randomUUID();
  private static final UUID CRED = UUID.randomUUID();

  @Nested
  class Records {

    @Test
    void records_reject_null_required_fields() {
      assertNpe("occurredAt", () -> new SignInSucceeded(null, SignInMethod.JWT, ID, CRED));
      assertNpe("method", () -> new SignInSucceeded(NOW, null, ID, CRED));
      assertNpe("identityId", () -> new SignInSucceeded(NOW, SignInMethod.JWT, null, CRED));
      assertNpe("credentialId", () -> new SignInSucceeded(NOW, SignInMethod.JWT, ID, null));

      assertNpe(
          "occurredAt",
          () ->
              new SignInFailed(
                  null, SignInMethod.JWT, SignInFailureReason.MALFORMED, null, null, null));
      assertNpe(
          "method",
          () -> new SignInFailed(NOW, null, SignInFailureReason.MALFORMED, null, null, null));
      assertNpe("reason", () -> new SignInFailed(NOW, SignInMethod.JWT, null, null, null, null));

      assertNpe("occurredAt", () -> new SignedOut(null, ID, CRED));
      assertNpe("identityId", () -> new SignedOut(NOW, null, CRED));
      assertNpe("credentialId", () -> new SignedOut(NOW, ID, null));

      SessionEndReason why = SessionEndReason.IDENTITY_INACTIVE;
      assertNpe("occurredAt", () -> new SessionEnded(null, ID, CRED, why));
      assertNpe("identityId", () -> new SessionEnded(NOW, null, CRED, why));
      assertNpe("credentialId", () -> new SessionEnded(NOW, ID, null, why));
      assertNpe("reason", () -> new SessionEnded(NOW, ID, CRED, null));

      assertNpe("occurredAt", () -> new IdentityCreated(null, ID));
      assertNpe("identityId", () -> new IdentityCreated(NOW, null));
      assertNpe("occurredAt", () -> new IdentityDeactivated(null, ID));
      assertNpe("identityId", () -> new IdentityDeactivated(NOW, null));
      assertNpe("occurredAt", () -> new IdentityReactivated(null, ID));
      assertNpe("identityId", () -> new IdentityReactivated(NOW, null));
      assertNpe("occurredAt", () -> new IdentityDeleted(null, ID));
      assertNpe("identityId", () -> new IdentityDeleted(NOW, null));

      assertNpe("occurredAt", () -> new OrphanedCredentialsPurged(null, 1));

      CredentialKind kind = CredentialKind.PASSKEY;
      assertNpe("occurredAt", () -> new CredentialAdded(null, ID, CRED, kind, null));
      assertNpe("identityId", () -> new CredentialAdded(NOW, null, CRED, kind, null));
      assertNpe("credentialId", () -> new CredentialAdded(NOW, ID, null, kind, null));
      assertNpe("kind", () -> new CredentialAdded(NOW, ID, CRED, null, null));
      assertNpe("occurredAt", () -> new CredentialRevoked(null, ID, CRED, kind, null));
      assertNpe("identityId", () -> new CredentialRevoked(NOW, null, CRED, kind, null));
      assertNpe("credentialId", () -> new CredentialRevoked(NOW, ID, null, kind, null));
      assertNpe("kind", () -> new CredentialRevoked(NOW, ID, CRED, null, null));
    }

    @Test
    void sign_in_failed_allows_null_address_identity_and_credential() {
      SignInFailed event =
          new SignInFailed(
              NOW, SignInMethod.EMAIL_CODE, SignInFailureReason.WRONG_CODE, null, null, null);

      assertThat(event.address()).isNull();
      assertThat(event.identityId()).isNull();
      assertThat(event.credentialId()).isNull();
      assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    private static void assertNpe(String name, Runnable construction) {
      assertThatNullPointerException()
          .isThrownBy(construction::run)
          .withMessage(name + " must not be null");
    }
  }

  @Nested
  class Publisher {

    @Test
    void noop_publisher_accepts_any_event() {
      assertThatCode(() -> WhoEventPublisher.NOOP.publish(new IdentityCreated(NOW, ID)))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  class Credentials {

    @Test
    void a_lambda_credential_is_of_kind_other_with_no_label() {
      Credential c = () -> ID;

      assertThat(c.kind()).isEqualTo(CredentialKind.OTHER);
      assertThat(c.label()).isEmpty();
    }
  }
}
