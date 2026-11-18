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

import org.jwcarman.who.spring.security.web.WhoProblem;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image reflection for {@link WhoProblem}. who's filters and entry point write it by hand,
 * so no controller method's signature registers it the way Spring registers a returned type.
 */
public class WhoProblemRuntimeHints implements RuntimeHintsRegistrar {

  /** Creates the registrar; Spring calls it, listed in {@code aot.factories}. */
  public WhoProblemRuntimeHints() {
    // stateless: the hints are registered by registerHints
  }

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    new BindingReflectionHintsRegistrar()
        .registerReflectionHints(hints.reflection(), WhoProblem.class);
  }
}
