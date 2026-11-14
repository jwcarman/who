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

  private static final Logger LOG = System.getLogger(WhoObservations.class.getName());

  private WhoObservations() {}

  /**
   * Runs {@code work} inside an observation named {@code name}, tagged {@value #OUTCOME} with the
   * value {@code outcome} gives for the result.
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
    boolean started = attempt(observation::start);
    Observation.Scope scope = started ? openScope(observation) : null;
    try {
      T result = work.get();
      observation.lowCardinalityKeyValue(OUTCOME, outcome.apply(result));
      return result;
    } catch (RuntimeException e) {
      if (started) {
        attempt(() -> observation.error(e));
      }
      throw e;
    } finally {
      if (scope != null) {
        attempt(scope::close);
      }
      if (started) {
        attempt(observation::stop);
      }
    }
  }

  private static Observation.Scope openScope(Observation observation) {
    try {
      return observation.openScope();
    } catch (RuntimeException e) {
      handlerFailed(e);
      return null;
    }
  }

  private static boolean attempt(Runnable step) {
    try {
      step.run();
      return true;
    } catch (RuntimeException e) {
      handlerFailed(e);
      return false;
    }
  }

  private static void handlerFailed(RuntimeException e) {
    // Class name only: a handler's message could carry anything the context holds.
    LOG.log(Level.WARNING, "An observation handler failed: {0}", e.getClass().getName());
  }
}
