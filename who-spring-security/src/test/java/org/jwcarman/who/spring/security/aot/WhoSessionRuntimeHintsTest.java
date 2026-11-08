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
package org.jwcarman.who.spring.security.aot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.WhoSession;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

class WhoSessionRuntimeHintsTest {

  @Test
  void whoSessionTypesAreSerializable() {
    RuntimeHints hints = new RuntimeHints();
    new WhoSessionRuntimeHints().registerHints(hints, getClass().getClassLoader());

    for (Class<?> type : new Class<?>[] {WhoSession.class, UUID.class, Instant.class}) {
      assertThat(RuntimeHintsPredicates.serialization().onType(type))
          .as(type.getName())
          .accepts(hints);
    }
    assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of("java.time.Ser")))
        .accepts(hints);
  }

  @Test
  void aotFactoriesRegistersWhoSessionHints() {
    assertThat(
            SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class))
        .hasAtLeastOneElementOfType(WhoSessionRuntimeHints.class);
  }
}
