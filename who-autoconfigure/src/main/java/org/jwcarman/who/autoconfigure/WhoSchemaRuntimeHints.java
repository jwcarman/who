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

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints for {@code who.initialize-schema}. {@link
 * WhoDataSourceScriptDatabaseInitializer} decides which modules' schema scripts to run by looking
 * up a class from each module by name. A native image finds a class by name only when it is
 * registered, so without these hints no script would run and the tables would be missing.
 */
public class WhoSchemaRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (String marker : WhoDataSourceScriptDatabaseInitializer.moduleMarkers()) {
      hints.reflection().registerTypeIfPresent(classLoader, marker, type -> {});
    }
  }
}
