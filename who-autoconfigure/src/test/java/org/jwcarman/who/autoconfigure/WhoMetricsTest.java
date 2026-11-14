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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.IdentityCreated;
import org.jwcarman.who.core.event.SessionEndReason;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.emaillink.EmailLinkOutcome;
import org.jwcarman.who.emaillink.SignInEmailRequested;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoMetricsTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
  private static final UUID IDENTITY = UUID.randomUUID();
  private static final UUID CREDENTIAL = UUID.randomUUID();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WhoMetrics metrics = new WhoMetrics(registry);

  private double count(String name, String... tags) {
    return registry.get(name).tags(tags).counter().count();
  }

  @Nested
  class Sign_ins {

    @Test
    void a_success_counts_with_its_method_and_reason_none() {
      metrics.on(new SignInSucceeded(NOW, SignInMethod.EMAIL_CODE, IDENTITY, CREDENTIAL));

      assertThat(
              count("who.sign-ins", "method", "email-code", "outcome", "success", "reason", "none"))
          .isEqualTo(1.0);
    }

    @Test
    void a_failure_counts_with_its_method_and_reason() {
      metrics.on(
          new SignInFailed(
              NOW, SignInMethod.API_KEY, SignInFailureReason.UNKNOWN_KEY, null, null, null));

      assertThat(
              count(
                  "who.sign-ins",
                  "method",
                  "api-key",
                  "outcome",
                  "failure",
                  "reason",
                  "unknown-key"))
          .isEqualTo(1.0);
    }

    @Test
    void the_same_kind_twice_counts_two() {
      SignInFailed wrongCode =
          new SignInFailed(
              NOW,
              SignInMethod.EMAIL_CODE,
              SignInFailureReason.WRONG_CODE,
              "someone@example.com",
              null,
              null);

      metrics.on(wrongCode);
      metrics.on(wrongCode);

      assertThat(
              count(
                  "who.sign-ins",
                  "method",
                  "email-code",
                  "outcome",
                  "failure",
                  "reason",
                  "wrong-code"))
          .isEqualTo(2.0);
    }

    @Test
    void the_address_never_becomes_a_tag() {
      metrics.on(
          new SignInFailed(
              NOW,
              SignInMethod.EMAIL_LINK,
              SignInFailureReason.INVALID_LINK,
              "someone@example.com",
              IDENTITY,
              CREDENTIAL));

      assertThat(registry.getMeters())
          .singleElement()
          .satisfies(
              meter ->
                  assertThat(meter.getId().getTags())
                      .extracting(tag -> tag.getKey())
                      .containsExactlyInAnyOrder("method", "outcome", "reason"));
    }
  }

  @Nested
  class Sessions_and_credentials {

    @Test
    void a_sign_out_counts() {
      metrics.on(new SignedOut(NOW, IDENTITY, CREDENTIAL));

      assertThat(registry.get("who.sign-outs").counter().count()).isEqualTo(1.0);
    }

    @Test
    void an_ended_session_counts_with_its_reason() {
      metrics.on(new SessionEnded(NOW, IDENTITY, CREDENTIAL, SessionEndReason.CREDENTIAL_REVOKED));

      assertThat(count("who.sessions.ended", "reason", "credential-revoked")).isEqualTo(1.0);
    }

    @Test
    void an_added_credential_counts_with_its_kind() {
      metrics.on(new CredentialAdded(NOW, IDENTITY, CREDENTIAL, CredentialKind.PASSKEY, "Phone"));

      assertThat(count("who.credentials.added", "kind", "passkey")).isEqualTo(1.0);
    }

    @Test
    void a_revoked_credential_counts_with_its_kind() {
      metrics.on(new CredentialRevoked(NOW, IDENTITY, CREDENTIAL, CredentialKind.API_KEY, "CI"));

      assertThat(count("who.credentials.revoked", "kind", "api-key")).isEqualTo(1.0);
    }
  }

  @Nested
  class Other_events {

    @Test
    void register_no_meter() {
      metrics.on(new IdentityCreated(NOW, IDENTITY));

      assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void no_meter_exists_before_its_first_event() {
      assertThat(registry.getMeters()).isEmpty();
    }
  }

  @Nested
  class Sign_in_emails {

    private final WhoEmailLinkMetrics emailMetrics = new WhoEmailLinkMetrics(registry);

    @Test
    void a_sent_email_counts_with_outcome_sent() {
      emailMetrics.on(
          new SignInEmailRequested(NOW, "someone@example.com", EmailLinkOutcome.SENT, IDENTITY));

      assertThat(count("who.sign-in.emails", "outcome", "sent")).isEqualTo(1.0);
    }

    @Test
    void a_failed_send_counts_with_outcome_send_failed() {
      emailMetrics.on(
          new SignInEmailRequested(
              NOW, "someone@example.com", EmailLinkOutcome.SEND_FAILED, IDENTITY));

      assertThat(count("who.sign-in.emails", "outcome", "send-failed")).isEqualTo(1.0);
    }
  }
}
