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
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.jwcarman.who.rbac.JdbcRoleRepository;
import org.springframework.boot.test.context.FilteredClassLoader;

class WhoDataSourceScriptDatabaseInitializerTest {

  @Test
  void allModulesOnClasspathIncludesAllSchemaLocations() {
    assertThat(
            WhoDataSourceScriptDatabaseInitializer.detectSchemaLocations(
                getClass().getClassLoader()))
        .contains(
            "classpath:org/jwcarman/who/jdbc/schema.sql",
            "classpath:org/jwcarman/who/rbac/schema.sql",
            "classpath:org/jwcarman/who/jwt/schema.sql",
            "classpath:org/jwcarman/who/enrollment/schema.sql",
            "classpath:org/jwcarman/who/apikey/schema.sql");
  }

  @Test
  void absentModuleSchemaLocationIsExcluded() {
    FilteredClassLoader filteredClassLoader =
        new FilteredClassLoader(JdbcIdentityRepository.class, JdbcRoleRepository.class);

    assertThat(WhoDataSourceScriptDatabaseInitializer.detectSchemaLocations(filteredClassLoader))
        .doesNotContain(
            "classpath:org/jwcarman/who/jdbc/schema.sql",
            "classpath:org/jwcarman/who/rbac/schema.sql")
        .contains(
            "classpath:org/jwcarman/who/jwt/schema.sql",
            "classpath:org/jwcarman/who/enrollment/schema.sql",
            "classpath:org/jwcarman/who/apikey/schema.sql");
  }
}
