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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JdbcEmailAddressRequestRepositoryTest extends AbstractEmailLinkTest {

  private static final Duration INTERVAL = Duration.ofSeconds(60);
  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

  @Autowired private EmailAddressRequestRepository requests;
  @Autowired private IdentityRepository identities;

  private UUID identity() {
    return identities.save(Identity.create()).id();
  }

  private static EmailAddressRequest request(UUID identityId, String address, Instant at) {
    return new EmailAddressRequest(
        UUID.randomUUID(), identityId, address, null, at, at.plusSeconds(300));
  }

  private boolean save(EmailAddressRequest request, String tokenHash) {
    return requests.save(request, tokenHash, "code-" + tokenHash, INTERVAL);
  }

  @Nested
  class Saving {

    @Test
    void a_first_request_is_saved_and_its_token_consumed_once() {
      UUID alice = identity();
      EmailAddressRequest request = request(alice, "new@example.com", NOW);

      assertThat(save(request, "t1")).isTrue();

      assertThat(requests.consumeToken(alice, "t1", NOW)).contains(request);
      assertThat(requests.consumeToken(alice, "t1", NOW)).isEmpty();
    }

    @Test
    void keeps_the_address_a_change_replaces() {
      UUID alice = identity();
      UUID old = UUID.randomUUID();
      EmailAddressRequest change =
          new EmailAddressRequest(
              UUID.randomUUID(), alice, "new@example.com", old, NOW, NOW.plusSeconds(300));
      save(change, "t1");

      assertThat(requests.consumeToken(alice, "t1", NOW))
          .hasValueSatisfying(r -> assertThat(r.replacesCredentialId()).isEqualTo(old));
    }

    @Test
    void a_second_request_within_the_interval_changes_nothing() {
      UUID alice = identity();
      save(request(alice, "one@example.com", NOW), "t1");

      Instant later = NOW.plusSeconds(10);
      assertThat(save(request(alice, "two@example.com", later), "t2")).isFalse();

      assertThat(requests.consumeToken(alice, "t2", later)).isEmpty();
      assertThat(requests.consumeToken(alice, "t1", later)).isPresent();
    }

    @Test
    void a_second_request_after_the_interval_replaces_the_first() {
      UUID alice = identity();
      save(request(alice, "one@example.com", NOW), "t1");

      Instant later = NOW.plusSeconds(61);
      EmailAddressRequest second = request(alice, "two@example.com", later);
      assertThat(save(second, "t2")).isTrue();

      assertThat(requests.consumeToken(alice, "t1", later)).isEmpty();
      assertThat(requests.consumeToken(alice, "t2", later)).contains(second);
    }

    @Test
    void someone_else_asking_for_the_same_address_within_the_interval_is_refused() {
      UUID alice = identity();
      UUID mallory = identity();
      save(request(alice, "new@example.com", NOW), "t1");

      Instant later = NOW.plusSeconds(10);
      assertThat(save(request(mallory, "new@example.com", later), "t2")).isFalse();
      assertThat(save(request(mallory, "new@example.com", NOW.plusSeconds(61)), "t3")).isTrue();
    }
  }

  @Nested
  class A_token {

    @Test
    void is_consumed_only_by_the_identity_that_asked() {
      UUID alice = identity();
      UUID mallory = identity();
      save(request(alice, "new@example.com", NOW), "t1");

      assertThat(requests.consumeToken(mallory, "t1", NOW)).isEmpty();
      assertThat(requests.consumeToken(alice, "t1", NOW)).isPresent();
    }

    @Test
    void does_not_work_once_expired() {
      UUID alice = identity();
      save(request(alice, "new@example.com", NOW), "t1");

      assertThat(requests.consumeToken(alice, "t1", NOW.plusSeconds(300))).isEmpty();
    }
  }

  @Nested
  class A_code {

    @Test
    void attempt_returns_the_request_and_its_code_hash() {
      UUID alice = identity();
      EmailAddressRequest request = request(alice, "new@example.com", NOW);
      save(request, "t1");

      assertThat(requests.attemptCode(alice, 5, NOW))
          .contains(new EmailAddressCodeAttempt(request.id(), "code-t1"));
      assertThat(requests.consumeCode(request.id(), "code-t1", NOW)).contains(request);
      assertThat(requests.consumeCode(request.id(), "code-t1", NOW)).isEmpty();
    }

    @Test
    void attempts_run_out() {
      UUID alice = identity();
      save(request(alice, "new@example.com", NOW), "t1");

      assertThat(requests.attemptCode(alice, 2, NOW)).isPresent();
      assertThat(requests.attemptCode(alice, 2, NOW)).isPresent();
      assertThat(requests.attemptCode(alice, 2, NOW)).isEmpty();
    }

    @Test
    void a_wrong_hash_consumes_nothing() {
      UUID alice = identity();
      EmailAddressRequest request = request(alice, "new@example.com", NOW);
      save(request, "t1");

      assertThat(requests.consumeCode(request.id(), "wrong", NOW)).isEmpty();
      assertThat(requests.consumeToken(alice, "t1", NOW)).isPresent();
    }

    @Test
    void cannot_be_attempted_once_expired() {
      UUID alice = identity();
      save(request(alice, "new@example.com", NOW), "t1");

      assertThat(requests.attemptCode(alice, 5, NOW.plusSeconds(300))).isEmpty();
    }
  }

  @Test
  void delete_removes_the_request() {
    UUID alice = identity();
    EmailAddressRequest request = request(alice, "new@example.com", NOW);
    save(request, "t1");

    requests.delete(request.id());

    assertThat(requests.consumeToken(alice, "t1", NOW)).isEmpty();
  }
}
