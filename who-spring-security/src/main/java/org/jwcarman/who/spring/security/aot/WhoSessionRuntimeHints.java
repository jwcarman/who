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

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.spring.security.WhoSession;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Native-image serialization for {@link WhoSession}. Spring Session JDBC Java-serializes session
 * attributes, so the session record and what it holds need serialization hints.
 */
public class WhoSessionRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.serialization().registerType(WhoSession.class);
    hints.serialization().registerType(UUID.class);
    hints.serialization().registerType(Instant.class);
    // java.time.Ser is package-private, and Instant serializes through it.
    hints.serialization().registerType(TypeReference.of("java.time.Ser"));
  }
}
