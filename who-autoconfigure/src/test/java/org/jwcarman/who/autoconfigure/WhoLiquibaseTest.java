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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import liquibase.integration.spring.SpringLiquibase;

/** who's own Liquibase run, and its place ahead of the application's, on PostgreSQL. */
class WhoLiquibaseTest {

  private static final String APP_CHANGELOG = "classpath:app-changelog/references-who.yaml";

  private static final String EMPTY_CHANGELOG = "classpath:db/changelog/db.changelog-master.yaml";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  LiquibaseAutoConfiguration.class,
                  WhoMigrationsAutoConfiguration.class))
          .withPropertyValues(WhoTestPostgres.freshDatabaseProperties())
          .withPropertyValues("spring.liquibase.change-log=" + APP_CHANGELOG);

  @Test
  void createsWhoTablesAndRunsTheApplicationsChangelogAfterThem() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(WhoLiquibase.class);
          assertThat(tables(context)).contains("who_identity", "who_role", "app_note");
        });
  }

  @Test
  void bootsSpringLiquibaseDependsOnWhoLiquibase() {
    runner.run(
        context -> {
          String[] dependsOn =
              context.getBeanFactory().getBeanDefinition("liquibase").getDependsOn();
          assertThat(dependsOn).contains(context.getBeanNamesForType(WhoLiquibase.class)[0]);
        });
  }

  @Test
  void anApplicationsOwnSpringLiquibaseBeanAlsoWaits() {
    runner
        .withUserConfiguration(OwnLiquibase.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeanFactory().getBeanDefinition("appLiquibase").getDependsOn())
                  .contains(context.getBeanNamesForType(WhoLiquibase.class)[0]);
              assertThat(tables(context)).contains("who_identity", "app_note");
            });
  }

  @Test
  void recordsInTheApplicationsChangelogTable() {
    runner
        .withPropertyValues("spring.liquibase.database-change-log-table=app_changelog")
        .run(
            context ->
                assertThat(
                        count(
                            context,
                            "SELECT count(*) FROM app_changelog"
                                + " WHERE filename LIKE '%who-jdbc-changelog.xml'"))
                    .isPositive());
  }

  @Test
  void honoursTheDefaultSchema() throws SQLException {
    String url = WhoTestPostgres.newDatabaseUrl();
    execute(url, "CREATE SCHEMA app");
    runner
        .withPropertyValues("spring.datasource.url=" + url, "spring.liquibase.default-schema=app")
        .run(context -> assertThat(tablesIn(context, "app")).contains("who_identity"));
  }

  @Test
  void usesSpringLiquibaseUrlWhenSet() {
    String other = WhoTestPostgres.newDatabaseUrl();
    runner
        .withPropertyValues(
            "spring.liquibase.url=" + other,
            "spring.liquibase.user=" + WhoTestPostgres.username(),
            "spring.liquibase.password=" + WhoTestPostgres.password())
        .run(
            context -> {
              assertThat(tablesAt(other)).contains("who_identity");
              assertThat(tables(context)).doesNotContain("who_identity");
            });
  }

  @Test
  void usesTheLiquibaseDataSourceWhenThereIsOne() {
    String other = WhoTestPostgres.newDatabaseUrl();
    runner
        .withUserConfiguration(OwnLiquibaseDataSource.class)
        .withPropertyValues("test.liquibase-url=" + other)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(tablesAt(other)).contains("who_identity");
              assertThat(tables(context)).doesNotContain("who_identity");
            });
  }

  @Test
  void usesSpringLiquibaseUserOnThePrimaryDatabaseWhenNoUrlIsSet() throws SQLException {
    String url = WhoTestPostgres.newDatabaseUrl();
    execute(url, "CREATE ROLE who_migrator LOGIN PASSWORD 'migrator'");
    execute(url, "GRANT ALL ON SCHEMA public TO who_migrator");
    runner
        .withPropertyValues(
            "spring.datasource.url=" + url,
            "spring.liquibase.user=who_migrator",
            "spring.liquibase.password=migrator")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      new JdbcTemplate(context.getBean(DataSource.class))
                          .queryForObject(
                              "SELECT tableowner FROM pg_tables WHERE tablename = 'who_identity'",
                              String.class))
                  .isEqualTo("who_migrator");
            });
  }

  @Test
  void doesNotRunWhenSpringLiquibaseIsDisabled() {
    runner
        .withPropertyValues("spring.liquibase.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(WhoLiquibase.class);
              assertThat(tables(context)).doesNotContain("who_identity");
            });
  }

  @Test
  void doesNotRunWhenWhoMigrationsAreDisabled() {
    // the application's changelog references who's tables, which nothing creates here
    runner
        .withPropertyValues(
            "who.migrations.enabled=false", "spring.liquibase.change-log=" + EMPTY_CHANGELOG)
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WhoLiquibase.class));
  }

  @Test
  void doesNotRunWithoutWhoJdbc() {
    // the application's changelog references who's tables, which nothing creates here
    runner
        .withClassLoader(new FilteredClassLoader("org.jwcarman.who.jdbc."))
        .withPropertyValues("spring.liquibase.change-log=" + EMPTY_CHANGELOG)
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WhoLiquibase.class));
  }

  private static List<String> tables(AssertableApplicationContext context) {
    return tablesIn(context, "public");
  }

  private static List<String> tablesIn(AssertableApplicationContext context, String schema) {
    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
            String.class,
            schema);
  }

  private static long count(AssertableApplicationContext context, String sql) {
    Long count =
        new JdbcTemplate(context.getBean(DataSource.class)).queryForObject(sql, Long.class);
    return count == null ? 0 : count;
  }

  private static List<String> tablesAt(String url) throws SQLException {
    List<String> tables = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                url, WhoTestPostgres.username(), WhoTestPostgres.password());
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    return tables;
  }

  private static void execute(String url, String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                url, WhoTestPostgres.username(), WhoTestPostgres.password());
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnLiquibaseDataSource {

    @Bean
    @Primary
    DataSource dataSource(Environment environment) {
      return new DriverManagerDataSource(
          environment.getRequiredProperty("spring.datasource.url"),
          WhoTestPostgres.username(),
          WhoTestPostgres.password());
    }

    @Bean
    @LiquibaseDataSource
    DataSource liquibaseDataSource(Environment environment) {
      return new DriverManagerDataSource(
          environment.getRequiredProperty("test.liquibase-url"),
          WhoTestPostgres.username(),
          WhoTestPostgres.password());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnLiquibase {

    @Bean
    SpringLiquibase appLiquibase(DataSource dataSource) {
      SpringLiquibase liquibase = new SpringLiquibase();
      liquibase.setDataSource(dataSource);
      liquibase.setChangeLog(APP_CHANGELOG);
      return liquibase;
    }
  }
}
