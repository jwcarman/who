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
package org.jwcarman.who.spring.security;

/** The fallback name of a sign-in method that does not name itself. */
final class LoginMethodNames {

  private LoginMethodNames() {}

  /**
   * Returns a readable name for the class, the same on every run: its simple name, its binary name
   * without the package for an anonymous class (such as {@code Config$1}), and a lambda's name
   * without the address the JVM appends.
   */
  static String of(Class<?> type) {
    String name = type.getSimpleName();
    if (name.isEmpty()) {
      String binary = type.getName();
      name = binary.substring(binary.lastIndexOf('.') + 1);
    }
    int address = name.indexOf('/');
    return address < 0 ? name : name.substring(0, address);
  }
}
