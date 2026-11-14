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
package org.jwcarman.who.oidc.aot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.oidc.PendingSignUp;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcRuntimeHintsTest {

  @Test
  void a_pending_sign_up_can_be_serialized_into_a_session_store() {
    RuntimeHints hints = new RuntimeHints();
    new OidcRuntimeHints().registerHints(hints, getClass().getClassLoader());

    for (Class<?> type :
        new Class<?>[] {
          PendingSignUp.class,
          UUID.class,
          Instant.class,
          String.class,
          Boolean.class,
          Long.class,
          Integer.class,
          Double.class,
          Number.class
        }) {
      assertThat(RuntimeHintsPredicates.reflection().onJavaSerialization(type, true))
          .as(type.getName())
          .accepts(hints);
    }
    for (String type : new String[] {"java.time.Ser", "java.util.CollSer"}) {
      assertThat(
              RuntimeHintsPredicates.reflection().onJavaSerialization(TypeReference.of(type), true))
          .as(type)
          .accepts(hints);
    }
  }

  @Test
  void aot_factories_registers_the_hints() {
    assertThat(
            SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class))
        .hasAtLeastOneElementOfType(OidcRuntimeHints.class);
  }
}
