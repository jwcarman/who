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
package org.jwcarman.who.nativetests.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** A PostgreSQL database in a container, one per test class. */
public final class TestDatabase implements AutoCloseable {

  private final PostgreSQLContainer container;

  private TestDatabase(PostgreSQLContainer container) {
    this.container = container;
  }

  /**
   * Starts a fresh database.
   *
   * @return the running database
   */
  public static TestDatabase start() {
    PostgreSQLContainer container = new PostgreSQLContainer("postgres:16");
    container.start();
    return new TestDatabase(container);
  }

  /**
   * The JDBC URL the app connects to.
   *
   * @return the URL
   */
  public String jdbcUrl() {
    return container.getJdbcUrl();
  }

  /**
   * The database user.
   *
   * @return the user name
   */
  public String username() {
    return container.getUsername();
  }

  /**
   * The database user's password.
   *
   * @return the password
   */
  public String password() {
    return container.getPassword();
  }

  /** Stops and removes the container. */
  @Override
  public void close() {
    container.stop();
  }
}
