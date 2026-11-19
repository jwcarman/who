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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A {@code @JdbcTest} slice gets who's tables before the application's changelog, which references
 * them, runs.
 */
@JdbcTest(properties = "spring.liquibase.change-log=classpath:app-changelog/references-who.yaml")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WhoLiquibaseSliceTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    WhoTestPostgres.registerFreshDatabase(registry);
  }

  @Autowired private JdbcClient jdbcClient;

  @Test
  void createsWhoTablesInAJdbcTestSlice() {
    assertThat(
            jdbcClient
                .sql(
                    "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'public'")
                .query(String.class)
                .list())
        .contains("who_identity", "app_note");
  }

  @SpringBootConfiguration
  static class SliceApplication {}
}
