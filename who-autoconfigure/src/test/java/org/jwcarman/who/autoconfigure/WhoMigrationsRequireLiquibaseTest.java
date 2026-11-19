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

import org.junit.jupiter.api.Test;
import org.jwcarman.migratory.autoconfigure.MigratoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Startup fails, naming who's migrations, when who's modules are present but Liquibase is not. */
class WhoMigrationsRequireLiquibaseTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class, MigratoryAutoConfiguration.class))
          .withPropertyValues(WhoTestPostgres.freshDatabaseProperties());

  @Test
  void failsWithoutLiquibase() {
    runner
        .withClassLoader(
            new FilteredClassLoader("liquibase.", "org.springframework.boot.liquibase."))
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("who-jdbc")
                    .hasMessageContaining("Liquibase is not on the classpath"));
  }

  @Test
  void startsWithoutLiquibaseWhenMigratoryIsDisabled() {
    runner
        .withClassLoader(
            new FilteredClassLoader("liquibase.", "org.springframework.boot.liquibase."))
        .withPropertyValues("migratory.enabled=false")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
