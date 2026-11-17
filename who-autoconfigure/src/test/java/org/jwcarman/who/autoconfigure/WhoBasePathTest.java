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

import java.util.UUID;

import javax.sql.DataSource;

import org.h2.Driver;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoBasePathTest {

  @Configuration(proxyBeanMethods = false)
  static class Database {
    @Bean
    DataSource dataSource() {
      DataSource dataSource =
          new SimpleDriverDataSource(
              new Driver(),
              "jdbc:h2:mem:"
                  + UUID.randomUUID()
                  + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
      new ResourceDatabasePopulator(
              new ClassPathResource("org/jwcarman/who/jdbc/who-jdbc-schema.sql"))
          .execute(dataSource);
      return dataSource;
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class)
          .withPropertyValues(
              "who.email-link.enabled=false",
              "who.passkey.enabled=false",
              "who.admin.enabled=false");

  @Test
  void the_default_root_is_who() {
    runner.run(context -> assertThat(context.getBean(WhoPaths.class).root()).isEqualTo("/who"));
  }

  @Test
  void who_base_path_sets_the_root() {
    runner
        .withPropertyValues("who.base-path=/auth")
        .run(context -> assertThat(context.getBean(WhoPaths.class).root()).isEqualTo("/auth"));
  }

  @Test
  void a_bad_base_path_fails_startup_naming_the_property() {
    runner
        .withPropertyValues("who.base-path=/auth/")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasStackTraceContaining("who.base-path")
                    .hasStackTraceContaining("/auth/"));
  }
}
