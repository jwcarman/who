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
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.federated.JdbcFederatedCredentialRepository;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import liquibase.integration.spring.SpringLiquibase;

/** Upgrading a 0.18 database, whose credentials live in {@code who_jwt_credential}. */
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FederatedCredentialUpgradeTest {

  private static final String ISSUER = "https://issuer.example.com";

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Nested
  class Liquibase {

    @Test
    void renames_a_0_18_table_and_keeps_its_rows() throws Exception {
      String database = createDatabase();
      runChangelog(database, "classpath:upgrade/who-changelog-0.18.xml");
      UUID id = UUID.randomUUID();
      execute(
          database,
          "INSERT INTO who_jwt_credential (id, issuer, subject) VALUES ('%s', '%s', 'sub-1')"
              .formatted(id, ISSUER));

      runChangelog(database, "classpath:org/jwcarman/who/db/who-changelog.xml");

      assertThat(tableExists(database, "who_jwt_credential")).isFalse();
      assertThat(repository(database).findByIssuerAndSubject(ISSUER, "sub-1"))
          .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(id));
    }

    @Test
    void creates_the_table_on_an_empty_database() throws Exception {
      String database = createDatabase();

      runChangelog(database, "classpath:org/jwcarman/who/db/who-changelog.xml");

      assertThat(tableExists(database, "who_federated_credential")).isTrue();
      assertThat(tableExists(database, "who_jwt_credential")).isFalse();
    }
  }

  private static void runChangelog(String database, String changelog) throws Exception {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setResourceLoader(
        new DefaultResourceLoader(FederatedCredentialUpgradeTest.class.getClassLoader()));
    liquibase.setDataSource(dataSource(database));
    liquibase.setChangeLog(changelog);
    liquibase.afterPropertiesSet();
  }

  private static JdbcFederatedCredentialRepository repository(String database) {
    return new JdbcFederatedCredentialRepository(JdbcClient.create(dataSource(database)));
  }

  private static DriverManagerDataSource dataSource(String database) {
    return new DriverManagerDataSource(
        jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static String createDatabase() throws SQLException {
    String database = "who_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
    }
    return database;
  }

  private static void execute(String database, String sql) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static boolean tableExists(String database, String table) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = '%s'"
                    .formatted(table))) {
      rs.next();
      return rs.getLong(1) > 0;
    }
  }

  private static String jdbcUrl(String database) {
    return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
  }
}
