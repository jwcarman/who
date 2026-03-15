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

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.util.ClassUtils;

/**
 * Initializes Who module database schemas by running each module's bundled DDL script against the
 * configured {@link DataSource}.
 *
 * <p>Only modules present on the classpath contribute scripts. All scripts use {@code CREATE TABLE
 * IF NOT EXISTS}, so they are safe to run on every startup.
 *
 * <p>Controlled by the {@code who.initialize-schema} property ({@code always}, {@code embedded},
 * {@code never}; default {@code embedded}).
 */
public class WhoDataSourceScriptDatabaseInitializer extends DataSourceScriptDatabaseInitializer {

  private static final List<ModuleSchema> MODULE_SCHEMAS =
      List.of(
          new ModuleSchema(
              "org.jwcarman.who.jdbc.JdbcIdentityRepository",
              "classpath:org/jwcarman/who/jdbc/schema.sql"),
          new ModuleSchema(
              "org.jwcarman.who.rbac.JdbcRoleRepository",
              "classpath:org/jwcarman/who/rbac/schema.sql"),
          new ModuleSchema(
              "org.jwcarman.who.jwt.JdbcJwtCredentialRepository",
              "classpath:org/jwcarman/who/jwt/schema.sql"),
          new ModuleSchema(
              "org.jwcarman.who.enrollment.JdbcEnrollmentTokenRepository",
              "classpath:org/jwcarman/who/enrollment/schema.sql"),
          new ModuleSchema(
              "org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository",
              "classpath:org/jwcarman/who/apikey/schema.sql"));

  /**
   * Creates a new initializer.
   *
   * @param dataSource the data source to initialize
   * @param mode when to run scripts ({@code always}, {@code embedded}, or {@code never})
   */
  public WhoDataSourceScriptDatabaseInitializer(
      DataSource dataSource, DatabaseInitializationMode mode) {
    super(dataSource, settings(mode));
  }

  private static DatabaseInitializationSettings settings(DatabaseInitializationMode mode) {
    DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
    settings.setMode(mode);
    settings.setSchemaLocations(detectSchemaLocations());
    return settings;
  }

  private static List<String> detectSchemaLocations() {
    ClassLoader classLoader = WhoDataSourceScriptDatabaseInitializer.class.getClassLoader();
    List<String> locations = new ArrayList<>();
    for (ModuleSchema module : MODULE_SCHEMAS) {
      if (ClassUtils.isPresent(module.sentinelClass(), classLoader)) {
        locations.add(module.schemaLocation());
      }
    }
    return locations;
  }

  private record ModuleSchema(String sentinelClass, String schemaLocation) {}
}
