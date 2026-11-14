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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoObservationsTest {

  private static final String NAME = "who.test";

  @Nested
  class Observing {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void the_work_runs_inside_one_observation_tagged_with_its_outcome() {
      String result =
          WhoObservations.observe(registry, NAME, "who test", () -> "done", r -> "ok-" + r);

      assertThat(result).isEqualTo("done");
      TestObservationRegistryAssert.assertThat(registry)
          .hasNumberOfObservationsWithNameEqualTo(NAME, 1)
          .hasObservationWithNameEqualTo(NAME)
          .that()
          .hasContextualNameEqualTo("who test")
          .hasLowCardinalityKeyValue("outcome", "ok-done")
          .hasBeenStarted()
          .hasBeenStopped();
    }

    @Test
    void a_failing_work_propagates_and_is_recorded() {
      IllegalStateException fault = new IllegalStateException("work");

      assertThatThrownBy(
              () ->
                  WhoObservations.observe(
                      registry,
                      NAME,
                      "who test",
                      () -> {
                        throw fault;
                      },
                      r -> "unused"))
          .isSameAs(fault);

      TestObservationRegistryAssert.assertThat(registry)
          .hasObservationWithNameEqualTo(NAME)
          .that()
          .hasError(fault)
          .hasLowCardinalityKeyValue("outcome", "error")
          .hasBeenStopped();
    }
  }

  @Nested
  class Tag_values {

    @Test
    void are_the_constant_in_lower_case_with_hyphens() {
      assertThat(WhoObservations.tagValue(Phase.SCOPE_OPENED)).isEqualTo("scope-opened");
      assertThat(WhoObservations.tagValue(Phase.START)).isEqualTo("start");
    }
  }

  enum Phase {
    START,
    SCOPE_OPENED,
    SCOPE_CLOSED,
    ERROR,
    STOP
  }

  @Nested
  class A_failing_handler {

    private ObservationRegistry failingAt(Set<Phase> phases) {
      ObservationRegistry registry = ObservationRegistry.create();
      registry
          .observationConfig()
          .observationHandler(
              new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                private void fail(Phase phase) {
                  if (phases.contains(phase)) {
                    throw new IllegalStateException("handler " + phase);
                  }
                }

                @Override
                public void onStart(Observation.Context context) {
                  fail(Phase.START);
                }

                @Override
                public void onScopeOpened(Observation.Context context) {
                  fail(Phase.SCOPE_OPENED);
                }

                @Override
                public void onScopeClosed(Observation.Context context) {
                  fail(Phase.SCOPE_CLOSED);
                }

                @Override
                public void onError(Observation.Context context) {
                  fail(Phase.ERROR);
                }

                @Override
                public void onStop(Observation.Context context) {
                  fail(Phase.STOP);
                }
              });
      return registry;
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void never_changes_the_result(Phase phase) {
      String result =
          WhoObservations.observe(
              failingAt(EnumSet.of(phase)), NAME, "who test", () -> "done", r -> "ok");

      assertThat(result).isEqualTo("done");
    }

    @ParameterizedTest
    @EnumSource(Phase.class)
    void never_replaces_the_works_own_exception(Phase phase) {
      IllegalArgumentException fault = new IllegalArgumentException("work");

      assertThatThrownBy(
              () ->
                  WhoObservations.observe(
                      failingAt(EnumSet.of(phase)),
                      NAME,
                      "who test",
                      () -> {
                        throw fault;
                      },
                      r -> "unused"))
          .isSameAs(fault);
    }

    @Test
    void failing_on_start_leaves_no_long_task_running() {
      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      ObservationRegistry registry = ObservationRegistry.create();
      registry
          .observationConfig()
          .observationHandler(new DefaultMeterObservationHandler(meters))
          .observationHandler(
              new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStart(Observation.Context context) {
                  throw new IllegalStateException("handler start");
                }
              });

      WhoObservations.observe(registry, NAME, "who test", () -> "done", r -> "ok");

      assertThat(meters.get(NAME + ".active").longTaskTimer().activeTasks()).isZero();
    }

    @Test
    void failing_on_scope_open_leaves_no_scope_behind() {
      ObservationRegistry registry = failingAt(EnumSet.of(Phase.SCOPE_OPENED));

      WhoObservations.observe(registry, NAME, "who test", () -> "done", r -> "ok");

      assertThat(registry.getCurrentObservationScope()).isNull();
      assertThat(registry.getCurrentObservation()).isNull();
    }

    @Test
    void failing_everywhere_still_answers() {
      String result =
          WhoObservations.observe(
              failingAt(EnumSet.allOf(Phase.class)), NAME, "who test", () -> "done", r -> "ok");

      assertThat(result).isEqualTo("done");
    }
  }
}
