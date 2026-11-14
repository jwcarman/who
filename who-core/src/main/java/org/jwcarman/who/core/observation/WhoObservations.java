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
package org.jwcarman.who.core.observation;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Runs who's own work inside a Micrometer {@link Observation} tagged with its outcome, so that a
 * failing observation handler never changes who's answer.
 *
 * <p>Micrometer calls handlers on the observed thread and does not catch what they throw. who
 * catches it here: a handler failure is logged at WARN (exception class only) and the work's
 * result, or the work's own exception, is what the caller gets. Used by who's modules; applications
 * observe who through the observation names who documents, not through this class.
 */
public final class WhoObservations {

  /** The one low-cardinality key on who's observations. */
  public static final String OUTCOME = "outcome";

  /** The {@value #OUTCOME} of work that threw. */
  public static final String ERROR = "error";

  private static final Logger LOG = System.getLogger(WhoObservations.class.getName());

  private WhoObservations() {}

  /**
   * Runs {@code work} inside an observation named {@code name}, tagged {@value #OUTCOME} with the
   * value {@code outcome} gives for the result, or {@value #ERROR} when {@code work} throws.
   *
   * @param registry where the observation is recorded; {@link ObservationRegistry#NOOP} records
   *     nothing
   * @param name the observation's name
   * @param contextualName the observation's contextual name, used for spans
   * @param work the work to observe
   * @param outcome the outcome tag value for the work's result
   * @param <T> the work's result type
   * @return what {@code work} returned
   */
  public static <T> T observe(
      ObservationRegistry registry,
      String name,
      String contextualName,
      Supplier<T> work,
      Function<? super T, String> outcome) {
    Observation observation =
        Observation.createNotStarted(name, registry).contextualName(contextualName);
    // Stop is attempted even when start fails: handlers that started before the failing one
    // (a long-task timer, say) must still be stopped.
    attempt(observation::start);
    Observation.Scope scope = openScope(registry, observation);
    try {
      T result = work.get();
      observation.lowCardinalityKeyValue(OUTCOME, outcome.apply(result));
      return result;
    } catch (RuntimeException e) {
      // Every series keeps the same tag keys: a failure is outcome=error.
      observation.lowCardinalityKeyValue(OUTCOME, ERROR);
      attempt(() -> observation.error(e));
      throw e;
    } finally {
      if (scope != null) {
        attempt(scope::close);
      }
      attempt(observation::stop);
    }
  }

  /**
   * Returns the tag value who uses for an enum constant: its name in lower case, with {@code _}
   * replaced by {@code -} ({@code EMAIL_CODE} is {@code email-code}).
   *
   * @param value the constant
   * @return its tag value
   */
  public static String tagValue(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * Opens the observation's scope. Micrometer makes the scope current before it notifies handlers,
   * so if a handler throws, the scope that was current before is put back.
   */
  private static Observation.Scope openScope(
      ObservationRegistry registry, Observation observation) {
    Observation.Scope previous = registry.getCurrentObservationScope();
    try {
      return observation.openScope();
    } catch (RuntimeException e) {
      handlerFailed(e);
      registry.setCurrentObservationScope(previous);
      return null;
    }
  }

  private static void attempt(Runnable step) {
    try {
      step.run();
    } catch (RuntimeException e) {
      handlerFailed(e);
    }
  }

  private static void handlerFailed(RuntimeException e) {
    // Class name only: a handler's message could carry anything the context holds.
    LOG.log(Level.WARNING, "An observation handler failed: {0}", e.getClass().getName());
  }
}
