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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LinkIntentTest {

  private static final Instant STARTED = Instant.parse("2026-11-15T12:00:00Z");
  private static final LinkIntent INTENT = new LinkIntent(UUID.randomUUID(), STARTED);

  @Nested
  class Attributes {

    @Test
    void survive_a_round_trip() {
      Map<String, Object> attributes = new HashMap<>();
      INTENT.writeTo(attributes);

      assertThat(LinkIntent.readFrom(attributes)).contains(INTENT);
      assertThat(attributes.values()).allSatisfy(v -> assertThat(v).isInstanceOf(String.class));
    }

    @Test
    void none_is_a_plain_sign_in() {
      assertThat(LinkIntent.readFrom(Map.of())).isEmpty();
    }

    @Test
    void only_half_an_intent_is_a_plain_sign_in() {
      Map<String, Object> attributes = new HashMap<>();
      INTENT.writeTo(attributes);
      attributes.keySet().removeIf(k -> k.endsWith("started-at"));

      assertThat(LinkIntent.readFrom(attributes)).isEmpty();
    }

    @Test
    void malformed_values_are_a_plain_sign_in() {
      Map<String, Object> attributes = new HashMap<>();
      INTENT.writeTo(attributes);
      attributes.replaceAll((k, v) -> "not a value");

      assertThat(LinkIntent.readFrom(attributes)).isEmpty();
    }
  }

  @Nested
  class Fresh_login {

    @Test
    void after_the_link_started_is_fresh() {
      assertThat(INTENT.freshLogin(STARTED.plusSeconds(5))).isTrue();
    }

    @Test
    void within_the_skew_before_it_is_fresh() {
      assertThat(INTENT.freshLogin(STARTED.minus(LinkIntent.SKEW))).isTrue();
    }

    @Test
    void past_the_skew_is_not() {
      assertThat(INTENT.freshLogin(STARTED.minus(LinkIntent.SKEW).minusSeconds(1))).isFalse();
    }

    @Test
    void no_auth_time_is_not() {
      assertThat(INTENT.freshLogin(null)).isFalse();
    }
  }

  @Nested
  class Expiry {

    private static final Duration LIFETIME = Duration.ofMinutes(10);

    @Test
    void within_the_lifetime_it_holds() {
      assertThat(INTENT.expired(STARTED.plus(LIFETIME), LIFETIME)).isFalse();
    }

    @Test
    void after_the_lifetime_it_has_expired() {
      assertThat(INTENT.expired(STARTED.plus(LIFETIME).plusSeconds(1), LIFETIME)).isTrue();
    }
  }
}
