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
package org.jwcarman.who.core.id;

import java.util.UUID;

import com.fasterxml.uuid.Generators;

/** Utility methods for generating identifiers. */
public final class Identifiers {

  private Identifiers() {}

  /**
   * Generates a new time-based (UUIDv7) identifier.
   *
   * @return a new unique UUID
   */
  public static UUID uuid() {
    return Generators.timeBasedEpochGenerator().generate();
  }
}
