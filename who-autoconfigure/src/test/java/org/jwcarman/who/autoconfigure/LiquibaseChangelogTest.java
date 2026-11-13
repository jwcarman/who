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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import liquibase.integration.spring.SpringLiquibase;

@Testcontainers
class LiquibaseChangelogTest {

  private static final String CHANGELOG_ALL = "org/jwcarman/who/db/who-changelog.xml";

  private static final List<String> SCHEMA_FILES =
      List.of(
          "org/jwcarman/who/jdbc/who-jdbc-schema.sql",
          "org/jwcarman/who/rbac/who-rbac-schema.sql",
          "org/jwcarman/who/jwt/who-jwt-schema.sql",
          "org/jwcarman/who/enrollment/who-enrollment-schema.sql",
          "org/jwcarman/who/apikey/who-apikey-schema.sql",
          "org/jwcarman/who/emaillink/who-email-link-schema.sql",
          "org/jwcarman/who/passkey/who-passkey-schema.sql");

  private static final String EMAIL_LINK_CHANGELOG =
      "org/jwcarman/who/emaillink/db/who-email-link-changelog.xml";

  private static final List<String> ALL_TABLES =
      List.of(
          "who_identity",
          "who_credential_identity",
          "who_role",
          "who_role_permission",
          "who_identity_role",
          "who_jwt_credential",
          "who_enrollment_token",
          "who_api_key_credential",
          "who_email_credential",
          "who_email_link_token",
          "who_passkey_user",
          "who_passkey_credential");

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Test
  void changelogsMatchSchemaSql() throws Exception {
    createDatabase("who_liquibase");
    createDatabase("who_sql");

    runChangelog("who_liquibase", getClass().getClassLoader());
    try (Connection connection = connect("who_sql");
        Statement statement = connection.createStatement()) {
      for (String schemaFile : SCHEMA_FILES) {
        statement.execute(readResource(schemaFile));
      }
    }

    List<String> fromLiquibase = describe("who_liquibase");
    List<String> fromSql = describe("who_sql");

    assertThat(tableNames(fromLiquibase)).containsExactlyInAnyOrderElementsOf(ALL_TABLES);
    assertThat(fromSql).isNotEmpty();
    assertThat(fromLiquibase).isEqualTo(fromSql);
  }

  @Test
  void changelogAllSkipsAbsentModules() throws Exception {
    createDatabase("who_skip");

    runChangelog("who_skip", new HidingClassLoader(EMAIL_LINK_CHANGELOG));

    List<String> description = describe("who_skip");
    assertThat(tableNames(description))
        .containsExactlyInAnyOrderElementsOf(
            ALL_TABLES.stream().filter(t -> !t.startsWith("who_email_")).toList());
  }

  @Test
  void changeSetTwoUpgradesATableWithRows() throws Exception {
    createDatabase("who_upgrade");
    createDatabase("who_fresh");
    runChangelog("who_upgrade", getClass().getClassLoader());

    try (Connection connection = connect("who_upgrade");
        Statement statement = connection.createStatement()) {
      // simulate a 0.14.0 database: change set 2 not yet run, its columns absent, a link pending
      statement.execute("DELETE FROM databasechangelog WHERE id = 'who-email-link-token-2'");
      statement.execute(
          "ALTER TABLE who_email_link_token DROP COLUMN code_hash, DROP COLUMN code_attempts");
      statement.execute(
          """
          INSERT INTO who_email_credential (id, address)
          VALUES ('00000000-0000-0000-0000-000000000001', 'owner@example.com')
          """);
      statement.execute(
          """
          INSERT INTO who_email_link_token (credential_id, token_hash, created_at, expires_at)
          VALUES ('00000000-0000-0000-0000-000000000001', 'hash', now(), now() + interval '15 minutes')
          """);
    }

    runChangelog("who_upgrade", getClass().getClassLoader());
    runChangelog("who_fresh", getClass().getClassLoader());

    try (Connection connection = connect("who_upgrade");
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM who_email_link_token")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isZero();
    }
    assertThat(describe("who_upgrade"))
        .contains(
            "who_email_link_token|column|code_hash|character varying|NO",
            "who_email_link_token|column|code_attempts|integer|NO")
        .isEqualTo(describe("who_fresh"));
  }

  private static void runChangelog(String database, ClassLoader classLoader) throws Exception {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setResourceLoader(new DefaultResourceLoader(classLoader));
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog("classpath:" + CHANGELOG_ALL);
    liquibase.afterPropertiesSet();
  }

  private static Set<String> tableNames(List<String> rows) {
    return rows.stream().map(r -> r.substring(0, r.indexOf('|'))).collect(Collectors.toSet());
  }

  private static List<String> describe(String database) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      try (ResultSet rs =
          statement.executeQuery(
              """
              SELECT table_name, column_name, data_type, is_nullable
              FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name LIKE 'who\\_%'
              """)) {
        while (rs.next()) {
          rows.add(
              "%s|column|%s|%s|%s"
                  .formatted(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
      }
      try (ResultSet rs =
          statement.executeQuery(
              """
              SELECT tc.table_name, tc.constraint_type, kcu.column_name,
                     ccu.table_name, ccu.column_name, rc.delete_rule
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_name = tc.constraint_name
               AND kcu.constraint_schema = tc.constraint_schema
              LEFT JOIN information_schema.referential_constraints rc
                ON rc.constraint_name = tc.constraint_name
               AND rc.constraint_schema = tc.constraint_schema
              LEFT JOIN information_schema.constraint_column_usage ccu
                ON ccu.constraint_name = rc.constraint_name
               AND ccu.constraint_schema = rc.constraint_schema
              WHERE tc.table_schema = 'public' AND tc.table_name LIKE 'who\\_%'
              """)) {
        while (rs.next()) {
          rows.add(
              "%s|%s|%s|%s|%s|%s"
                  .formatted(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getString(5),
                      rs.getString(6)));
        }
      }
    }
    rows.sort(null);
    return rows;
  }

  private static void createDatabase(String name) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
  }

  private static String jdbcUrl(String database) {
    return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
  }

  private static Connection connect(String database) throws SQLException {
    return DriverManager.getConnection(
        jdbcUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private String readResource(String path) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static final class HidingClassLoader extends ClassLoader {
    private final String hidden;

    HidingClassLoader(String hidden) {
      super(LiquibaseChangelogTest.class.getClassLoader());
      this.hidden = hidden;
    }

    @Override
    public URL getResource(String name) {
      return hidden.equals(name) ? null : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      return hidden.equals(name) ? Collections.emptyEnumeration() : super.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
      return hidden.equals(name) ? null : super.getResourceAsStream(name);
    }
  }
}
