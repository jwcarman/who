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

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.jwcarman.migratory.autoconfigure.MigratoryAutoConfiguration;
import org.jwcarman.migratory.autoconfigure.MigratoryLiquibase;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** migratory runs who's changelogs ahead of the application's, on PostgreSQL. */
class WhoMigrationsTest {

  private static final String APP_CHANGELOG = "classpath:app-changelog/references-who.yaml";

  private static final String EMPTY_CHANGELOG = "classpath:db/changelog/db.changelog-master.yaml";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  LiquibaseAutoConfiguration.class,
                  MigratoryAutoConfiguration.class))
          .withPropertyValues(WhoTestPostgres.freshDatabaseProperties())
          .withPropertyValues("spring.liquibase.change-log=" + APP_CHANGELOG);

  @Test
  void createsWhoTablesAndRunsTheApplicationsChangelogAfterThem() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(MigratoryLiquibase.class);
          assertThat(tables(context))
              .contains(
                  "who_identity",
                  "who_role",
                  "who_federated_credential",
                  "who_enrollment_token",
                  "who_api_key_credential",
                  "who_email_credential",
                  "who_passkey_user",
                  "app_note");
        });
  }

  @Test
  void bootsSpringLiquibaseDependsOnMigratory() {
    runner.run(
        context ->
            assertThat(context.getBeanFactory().getBeanDefinition("liquibase").getDependsOn())
                .contains(context.getBeanNamesForType(MigratoryLiquibase.class)[0]));
  }

  @Test
  void createsNoWhoTablesWhenMigratoryIsDisabled() {
    // the application's changelog references who's tables, which nothing creates here
    runner
        .withPropertyValues(
            "migratory.enabled=false", "spring.liquibase.change-log=" + EMPTY_CHANGELOG)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(tables(context)).doesNotContain("who_identity");
            });
  }

  private static List<String> tables(AssertableApplicationContext context) {
    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);
  }
}
