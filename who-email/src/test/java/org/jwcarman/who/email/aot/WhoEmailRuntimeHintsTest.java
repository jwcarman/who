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
package org.jwcarman.who.email.aot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoEmailRuntimeHintsTest {

  private static RuntimeHints hints() {
    RuntimeHints hints = new RuntimeHints();
    new WhoEmailRuntimeHints()
        .registerHints(hints, WhoEmailRuntimeHintsTest.class.getClassLoader());
    return hints;
  }

  @Test
  void the_default_template_location_is_readable_in_a_native_image() {
    RuntimeHints hints = hints();

    assertThat(RuntimeHintsPredicates.resource().forResource("templates/who/email/sign-in.txt"))
        .accepts(hints);
    assertThat(RuntimeHintsPredicates.resource().forResource("templates/who/email/sign-in.html"))
        .accepts(hints);
  }

  @Test
  void spring_aot_finds_the_registrar() {
    List<RuntimeHintsRegistrar> registrars =
        SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
            .load(RuntimeHintsRegistrar.class);

    assertThat(registrars).hasAtLeastOneElementOfType(WhoEmailRuntimeHints.class);
  }
}
