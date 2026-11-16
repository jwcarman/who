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

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the dashboard moving: every {@code example.traffic.interval} it picks a {@link Scenario} at
 * random, by weight, and runs it. On unless {@code example.traffic.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "example.traffic.enabled", matchIfMissing = true)
class TrafficGenerator {

  private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);

  private static final int TOTAL_WEIGHT =
      Arrays.stream(Scenario.values()).mapToInt(Scenario::weight).sum();

  private final TrafficScenarios scenarios;

  TrafficGenerator(TrafficScenarios scenarios) {
    this.scenarios = scenarios;
  }

  @Scheduled(
      initialDelayString = "${example.traffic.interval:1500ms}",
      fixedDelayString = "${example.traffic.interval:1500ms}")
  void tick() {
    if (scenarios.ready()) {
      attempt(pick());
    }
  }

  /**
   * Runs one scenario. A failure (Mailpit not up yet, say) is logged, and the next tick goes on.
   *
   * @param scenario what to do
   * @return whether it ran to the end
   */
  boolean attempt(Scenario scenario) {
    try {
      int status = scenarios.run(scenario);
      log.debug("Traffic {} answered {}", scenario, status);
      return true;
    } catch (RuntimeException e) {
      log.warn("Traffic {} failed: {}", scenario, e.getMessage());
      return false;
    }
  }

  private static Scenario pick() {
    int roll = ThreadLocalRandom.current().nextInt(TOTAL_WEIGHT);
    for (Scenario scenario : Scenario.values()) {
      roll -= scenario.weight();
      if (roll < 0) {
        return scenario;
      }
    }
    throw new IllegalStateException("Weights do not add up");
  }
}
