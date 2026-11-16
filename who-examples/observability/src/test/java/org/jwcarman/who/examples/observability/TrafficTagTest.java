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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** HTTP metrics say whether the generator or a person made the request. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TrafficTagTest extends AbstractObservabilityTest {

  @Autowired MeterRegistry meters;

  @Test
  void a_synthetic_request_is_counted_as_synthetic() throws Exception {
    mockMvc.perform(get("/api/greeting").header("X-Traffic", "synthetic"));

    assertThat(count("synthetic")).isPositive();
  }

  @Test
  void a_request_without_the_header_is_interactive() throws Exception {
    mockMvc.perform(get("/api/greeting"));

    assertThat(count("interactive")).isPositive();
  }

  @Test
  void any_other_value_is_interactive() throws Exception {
    double before = count("interactive");

    mockMvc.perform(get("/api/greeting").header("X-Traffic", "made-up"));

    assertThat(count("interactive")).isGreaterThan(before);
    assertThat(meters.find("http.server.requests").tag("traffic", "made-up").timer()).isNull();
  }

  private double count(String traffic) {
    return meters.find("http.server.requests").tag("traffic", traffic).timers().stream()
        .mapToDouble(Timer::count)
        .sum();
  }
}
