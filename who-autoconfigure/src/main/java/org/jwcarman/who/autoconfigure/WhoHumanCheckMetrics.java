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

import static org.jwcarman.who.core.observation.WhoObservations.tagValue;

import org.jwcarman.who.spring.security.HumanCheckPerformed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts human checks as {@value #HUMAN_CHECKS}, tagged {@code check}, {@code action} and {@code
 * outcome}. Separate from {@link WhoMetrics} so that applications without who-spring-security never
 * load its classes. The check's name and the action come from code; the client address is never a
 * tag.
 */
public final class WhoHumanCheckMetrics {

  /**
   * Requests to human-checked endpoints, tagged {@code check}, {@code action} and {@code outcome}.
   */
  public static final String HUMAN_CHECKS = "who.human-checks";

  private static final Logger log = LoggerFactory.getLogger(WhoHumanCheckMetrics.class);

  private final MeterRegistry registry;

  /**
   * Creates the listener.
   *
   * @param registry where the counter is registered
   */
  public WhoHumanCheckMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Counts one check.
   *
   * @param event the check and its outcome
   */
  @EventListener
  public void on(HumanCheckPerformed event) {
    try {
      Counter.builder(HUMAN_CHECKS)
          .description("Human checks by check, action and outcome")
          .tag("check", event.check())
          .tag("action", event.action())
          .tag("outcome", tagValue(event.outcome()))
          .register(registry)
          .increment();
    } catch (RuntimeException e) {
      // A failing registry must not keep the event from the application's own listeners.
      log.warn("Failed to count a human check: {}", e.getClass().getName());
    }
  }
}
