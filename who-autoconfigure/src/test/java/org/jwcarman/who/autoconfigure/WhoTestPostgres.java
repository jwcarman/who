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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by every test in the JVM, handing each caller a database of its
 * own so that who's tables start empty.
 */
final class WhoTestPostgres {

  // Singleton container: started once per JVM. @Container is intentionally omitted, because
  // Testcontainers stops @Container static fields between test classes. Spring caches up to 32
  // contexts, each with its own connection pool of up to 10, beyond Postgres's default of 100
  // connections.
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16").withCommand("postgres", "-c", "max_connections=400");

  private static final AtomicInteger DATABASES = new AtomicInteger();

  static {
    POSTGRES.start();
  }

  private WhoTestPostgres() {}

  /**
   * Creates an empty database on the shared container.
   *
   * @return the new database's JDBC URL
   */
  static String newDatabaseUrl() {
    String name = "who_" + DATABASES.incrementAndGet();
    try (Connection connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), username(), password());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create database " + name, e);
    }
    return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
  }

  /**
   * Returns the container's user name.
   *
   * @return the user name
   */
  static String username() {
    return POSTGRES.getUsername();
  }

  /**
   * Returns the container's password.
   *
   * @return the password
   */
  static String password() {
    return POSTGRES.getPassword();
  }

  /**
   * Returns Spring Boot data source properties pointing at a new, empty database.
   *
   * @return {@code spring.datasource.url}, {@code username} and {@code password}
   */
  static String[] freshDatabaseProperties() {
    return new String[] {
      "spring.datasource.url=" + newDatabaseUrl(),
      "spring.datasource.username=" + username(),
      "spring.datasource.password=" + password()
    };
  }

  /**
   * Points a {@code @SpringBootTest}'s data source at a new, empty database. Call it from a {@code
   * DynamicPropertySource} method, which Spring runs once for each context it loads.
   *
   * @param registry the registry to add {@code spring.datasource.url}, {@code username} and {@code
   *     password} to
   */
  static void registerFreshDatabase(DynamicPropertyRegistry registry) {
    String url = newDatabaseUrl();
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.username", WhoTestPostgres::username);
    registry.add("spring.datasource.password", WhoTestPostgres::password);
  }

  /**
   * Returns a data source on a new, empty database, for a test configuration that declares its own
   * {@code DataSource} bean.
   *
   * @return the data source
   */
  static DataSource newDataSource() {
    return new DriverManagerDataSource(newDatabaseUrl(), username(), password());
  }
}
