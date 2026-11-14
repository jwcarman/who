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

import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSchemaRuntimeHintsTest {

  /** The class each module's schema script is detected by, one per module with a schema. */
  private static final List<String> MARKERS =
      List.of(
          "org.jwcarman.who.jdbc.JdbcIdentityRepository",
          "org.jwcarman.who.rbac.JdbcRoleRepository",
          "org.jwcarman.who.jwt.JdbcJwtCredentialRepository",
          "org.jwcarman.who.enrollment.JdbcEnrollmentTokenRepository",
          "org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository",
          "org.jwcarman.who.emaillink.JdbcEmailCredentialRepository",
          "org.jwcarman.who.passkey.JdbcWhoPasskeyCredentialRepository");

  private static RuntimeHints hints(ClassLoader classLoader) {
    RuntimeHints hints = new RuntimeHints();
    new WhoSchemaRuntimeHints().registerHints(hints, classLoader);
    return hints;
  }

  @Nested
  class Registration {

    @Test
    void every_module_marker_on_the_classpath_can_be_looked_up_by_name() {
      RuntimeHints hints = hints(getClass().getClassLoader());

      // who.initialize-schema finds each module's script with Class.forName on its marker, which
      // a native image answers only for registered types.
      for (String marker : MARKERS) {
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(marker)))
            .as(marker)
            .accepts(hints);
      }
    }

    @Test
    void the_markers_are_the_ones_the_initializer_checks() {
      assertThat(WhoDataSourceScriptDatabaseInitializer.moduleMarkers())
          .containsExactlyInAnyOrderElementsOf(MARKERS);
    }

    @Test
    void a_module_that_is_not_on_the_classpath_is_skipped() {
      RuntimeHints hints = hints(new ClassLoader(null) {});

      assertThat(hints.reflection().typeHints()).isEmpty();
    }
  }

  @Nested
  class Discovery {

    @Test
    void spring_aot_finds_the_registrar() {
      List<RuntimeHintsRegistrar> registrars =
          SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
              .load(RuntimeHintsRegistrar.class);

      assertThat(registrars).hasAtLeastOneElementOfType(WhoSchemaRuntimeHints.class);
    }
  }
}
