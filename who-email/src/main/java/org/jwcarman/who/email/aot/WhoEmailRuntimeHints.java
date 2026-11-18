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

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for an application's email templates at the default {@code
 * who.email.templates} location, {@code classpath:/templates/who/email/}. A native image reads a
 * classpath resource only when it is registered; templates elsewhere need the application's own
 * hint.
 */
public class WhoEmailRuntimeHints implements RuntimeHintsRegistrar {

  /** Creates the registrar; Spring calls it, listed in {@code aot.factories}. */
  public WhoEmailRuntimeHints() {
    // stateless: the hints are registered by registerHints
  }

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    hints.resources().registerPattern("templates/who/email/*");
  }
}
