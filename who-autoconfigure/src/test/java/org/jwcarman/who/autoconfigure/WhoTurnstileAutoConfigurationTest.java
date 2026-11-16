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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.spring.security.WhoHumanCheck;
import org.jwcarman.who.turnstile.TurnstileHumanCheck;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoTurnstileAutoConfigurationTest {

  private static final String SECRET = "who.turnstile.secret-key=0x4AAAAAAAsecret";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WhoTurnstileAutoConfiguration.class))
          // this module's surefire turns Turnstile off for every other test
          .withPropertyValues("who.turnstile.enabled=true");

  @Test
  void the_jar_and_a_secret_give_a_turnstile_check() {
    runner
        .withPropertyValues(SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WhoHumanCheck.class))
                  .isInstanceOf(TurnstileHumanCheck.class);
            });
  }

  @Test
  void a_missing_secret_fails_startup_naming_the_property() {
    runner.run(WhoTurnstileAutoConfigurationTest::assertSecretRefused);
  }

  @Test
  void a_blank_secret_fails_startup() {
    runner
        .withPropertyValues("who.turnstile.secret-key= ")
        .run(WhoTurnstileAutoConfigurationTest::assertSecretRefused);
  }

  @Test
  void an_unresolved_placeholder_secret_fails_startup() {
    runner
        .withPropertyValues("who.turnstile.secret-key=${TURNSTILE_SECRET_NOT_SET}")
        .run(WhoTurnstileAutoConfigurationTest::assertSecretRefused);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-1s"})
  void a_timeout_that_is_not_positive_fails_startup(String timeout) {
    runner
        .withPropertyValues(SECRET, "who.turnstile.timeout=" + timeout)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOfSatisfying(
                        WhoConfigurationException.class,
                        e -> assertThat(e.property()).isEqualTo("who.turnstile.timeout")));
  }

  @Test
  void enabled_false_turns_it_off() {
    runner
        .withPropertyValues("who.turnstile.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(WhoHumanCheck.class);
            });
  }

  @Test
  void without_the_jar_there_is_no_check() {
    runner
        .withClassLoader(new FilteredClassLoader(TurnstileHumanCheck.class))
        .withPropertyValues(SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(WhoHumanCheck.class);
            });
  }

  @Configuration
  static class OwnCheck {
    @Bean
    WhoHumanCheck ownCheck() {
      return WhoHumanCheck.ALLOW_ALL;
    }
  }

  @Test
  void an_application_check_wins() {
    runner
        .withUserConfiguration(OwnCheck.class)
        .withPropertyValues(SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WhoHumanCheck.class)).isSameAs(WhoHumanCheck.ALLOW_ALL);
            });
  }

  private static void assertSecretRefused(AssertableApplicationContext context) {
    assertThat(context.getStartupFailure())
        .isNotNull()
        .rootCause()
        .isInstanceOfSatisfying(
            WhoConfigurationException.class,
            e -> {
              assertThat(e.property()).isEqualTo("who.turnstile.secret-key");
              assertThat(e.action()).contains("who.turnstile.enabled=false");
            });
  }
}
