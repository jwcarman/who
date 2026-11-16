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
package org.jwcarman.who.examples.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The generator's scenarios make real requests to the running app and get the answer each is meant
 * to show on the dashboard. Mailpit is not running here, so a scenario that reads a code fails, and
 * the generator carries on.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "example.traffic.enabled=false",
      "example.mailpit-url=http://localhost:1",
      "management.otlp.metrics.export.enabled=false",
      "management.opentelemetry.tracing.export.otlp.enabled=false",
      "management.opentelemetry.logging.export.otlp.enabled=false"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TrafficGeneratorTest {

  @ServiceConnection static final PostgreSQLContainer POSTGRES = AbstractObservabilityTest.POSTGRES;

  @Autowired TrafficScenarios scenarios;

  @Test
  void a_good_key_reads_the_greeting() {
    assertThat(scenarios.run(Scenario.GOOD_KEY)).isEqualTo(200);
  }

  @Test
  void a_key_without_the_scope_cannot_write() {
    assertThat(scenarios.run(Scenario.MISSING_SCOPE)).isEqualTo(403);
  }

  @Test
  void unknown_revoked_and_expired_keys_are_refused() {
    assertThat(scenarios.run(Scenario.UNKNOWN_KEY)).isEqualTo(401);
    assertThat(scenarios.run(Scenario.REVOKED_KEY)).isEqualTo(401);
    assertThat(scenarios.run(Scenario.EXPIRED_KEY)).isEqualTo(401);
  }

  @Test
  void a_wrong_code_is_refused() {
    assertThat(scenarios.run(Scenario.WRONG_CODE)).isEqualTo(401);
  }

  @Test
  void code_requests_are_answered_alike_for_known_and_unknown_addresses() {
    assertThat(scenarios.run(Scenario.CODE_FOR_KNOWN_ADDRESS))
        .isEqualTo(scenarios.run(Scenario.CODE_FOR_UNKNOWN_ADDRESS));
  }

  @Test
  void a_failing_scenario_does_not_stop_the_next() {
    TrafficGenerator generator = new TrafficGenerator(scenarios);

    assertThat(generator.attempt(Scenario.CODE_SIGN_IN)).isFalse();
    assertThat(generator.attempt(Scenario.GOOD_KEY)).isTrue();
  }
}
