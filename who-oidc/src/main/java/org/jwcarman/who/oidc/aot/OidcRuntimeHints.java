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

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.oidc.PendingSignUp;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Lets a native image keep a {@link PendingSignUp} in a session store that serializes its
 * attributes, such as Spring Session JDBC.
 */
public class OidcRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (Class<?> type :
        new Class<?>[] {
          PendingSignUp.class,
          UUID.class,
          Instant.class,
          String.class,
          Boolean.class,
          Number.class,
          Long.class,
          Integer.class,
          Double.class
        }) {
      hints.reflection().registerJavaSerialization(type);
    }
    // package-private: Instant serializes through java.time.Ser, Map.copyOf's maps through
    // java.util.CollSer
    for (String type : new String[] {"java.time.Ser", "java.util.CollSer"}) {
      hints
          .reflection()
          .registerType(TypeReference.of(type), builder -> builder.withJavaSerialization(true));
    }
  }
}
