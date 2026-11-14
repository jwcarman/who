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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.emaillink.SignInEmailRequested;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoMetricsAutoConfigurationTest {

  private static final SignInFailed FAILED =
      new SignInFailed(
          Instant.parse("2026-11-13T10:00:00Z"),
          SignInMethod.PASSKEY,
          SignInFailureReason.INVALID_ASSERTION,
          null,
          null,
          null);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WhoMetricsAutoConfiguration.class));

  @Nested
  class With_a_meter_registry {

    @Test
    void registers_both_listeners() {
      runner
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .run(
              context -> {
                assertThat(context).hasSingleBean(WhoMetrics.class);
                assertThat(context).hasSingleBean(WhoEmailLinkMetrics.class);
              });
    }

    @Test
    void counts_an_event_published_through_the_context() {
      runner
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .run(
              context -> {
                context.publishEvent(FAILED);

                assertThat(
                        context
                            .getBean(MeterRegistry.class)
                            .get("who.sign-ins")
                            .tags("method", "passkey", "reason", "invalid-assertion")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
              });
    }

    @Test
    void a_failing_registry_never_reaches_the_publisher() {
      SimpleMeterRegistry failing = new SimpleMeterRegistry();
      failing
          .config()
          .meterFilter(
              new MeterFilter() {
                @Override
                public Meter.Id map(Meter.Id id) {
                  throw new IllegalStateException("registry down");
                }
              });
      runner
          .withBean(MeterRegistry.class, () -> failing)
          .run(
              context -> {
                SpringWhoEventPublisher publisher = new SpringWhoEventPublisher(context);

                assertThatCode(() -> publisher.publish(FAILED)).doesNotThrowAnyException();
              });
    }
  }

  @Nested
  class Without_a_meter_registry_bean {

    @Test
    void registers_nothing() {
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WhoMetrics.class);
            assertThat(context).doesNotHaveBean(WhoEmailLinkMetrics.class);
          });
    }
  }

  @Nested
  class Without_micrometer_on_the_classpath {

    @Test
    void starts_and_registers_nothing() {
      runner
          .withClassLoader(new FilteredClassLoader(MeterRegistry.class))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WhoMetrics.class);
              });
    }
  }

  @Nested
  class Without_who_email_link {

    @Test
    void counts_the_rest_and_never_loads_email_link_classes() {
      runner
          .withClassLoader(new FilteredClassLoader(SignInEmailRequested.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(WhoMetrics.class);
                assertThat(context).doesNotHaveBean(WhoEmailLinkMetrics.class);
              });
    }
  }
}
