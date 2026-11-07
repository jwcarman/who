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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.Resource;

@Testcontainers
class LiquibaseChangelogTest {

  private static final String CHANGELOG_ALL = "org/jwcarman/who/db/changelog-all.xml";

  private static final List<String> SCHEMA_FILES =
      List.of(
          "org/jwcarman/who/jdbc/schema.sql",
          "org/jwcarman/who/rbac/schema.sql",
          "org/jwcarman/who/jwt/schema.sql",
          "org/jwcarman/who/enrollment/schema.sql",
          "org/jwcarman/who/apikey/schema.sql",
          "org/jwcarman/who/emaillink/schema.sql");

  private static final String EMAIL_LINK_CHANGELOG = "org/jwcarman/who/emaillink/db/changelog.xml";

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

    assertThat(fromSql).isNotEmpty();
    assertThat(fromLiquibase).isEqualTo(fromSql);
  }

  @Test
  void changelogAllSkipsAbsentModules() throws Exception {
    createDatabase("who_skip");

    runChangelog("who_skip", new HidingClassLoader(EMAIL_LINK_CHANGELOG));

    List<String> description = describe("who_skip");
    assertThat(description).anyMatch(row -> row.startsWith("who_identity|"));
    assertThat(description).noneMatch(row -> row.startsWith("who_email_"));
  }

  private static void runChangelog(String database, ClassLoader classLoader) throws Exception {
    try (Connection connection = connect(database)) {
      Database db =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      Scope.child(
          Map.of(
              Scope.Attr.resourceAccessor.name(), new ClasspathPrefixResourceAccessor(classLoader)),
          () ->
              new CommandScope("update")
                  .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, db)
                  .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG_ALL)
                  .execute());
    }
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
    Collections.sort(rows);
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

  private static Connection connect(String database) throws SQLException {
    String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
    return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private String readResource(String path) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Resolves {@code classpath:} paths the way Spring's resource accessor does in a real Boot app;
   * Liquibase's plain class loader accessor does not understand the prefix.
   */
  private static final class ClasspathPrefixResourceAccessor extends ClassLoaderResourceAccessor {
    private static final String PREFIX = "classpath:";

    ClasspathPrefixResourceAccessor(ClassLoader classLoader) {
      super(classLoader);
    }

    private static String strip(String path) {
      return path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : path;
    }

    @Override
    public List<Resource> getAll(String path) throws IOException {
      return super.getAll(strip(path));
    }

    @Override
    public List<Resource> search(String path, SearchOptions searchOptions) throws IOException {
      return super.search(strip(path), searchOptions);
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
