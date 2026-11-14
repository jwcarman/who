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

import org.jwcarman.who.emaillink.SignInEmailRequested;
import org.springframework.context.event.EventListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts sign-in email requests as {@value #SIGN_IN_EMAILS}, tagged {@code outcome}. Separate from
 * {@link WhoMetrics} so that applications without who-email-link never load its classes.
 */
public final class WhoEmailLinkMetrics {

  /** Sign-in email requests, tagged {@code outcome}. */
  public static final String SIGN_IN_EMAILS = "who.sign-in.emails";

  private final MeterRegistry registry;

  /**
   * Creates the listener.
   *
   * @param registry where the counter is registered
   */
  public WhoEmailLinkMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * Counts one request.
   *
   * @param event the request and its outcome
   */
  @EventListener
  public void on(SignInEmailRequested event) {
    Counter.builder(SIGN_IN_EMAILS)
        .description("Sign-in email requests by outcome")
        .tag("outcome", tagValue(event.outcome()))
        .register(registry)
        .increment();
  }
}
