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
package org.jwcarman.who.spring.security.web;

/** Makes request values safe to write into a log line. */
final class LogValues {

  private static final char LINE_SEPARATOR = (char) 0x2028;
  private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

  private LogValues() {}

  /**
   * The value with every control character escaped, so a request cannot end the log line and forge
   * another: {@code \r} and {@code \n} as themselves, any other control character and the Unicode
   * line and paragraph separators as {@code \}{@code uXXXX}.
   *
   * @param value a value the request chose, such as its URI
   * @return the value, printable on one line
   */
  static String printable(String value) {
    StringBuilder printable = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\r') {
        printable.append("\\r");
      } else if (c == '\n') {
        printable.append("\\n");
      } else if (Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR) {
        printable.append(String.format("\\u%04x", (int) c));
      } else {
        printable.append(c);
      }
    }
    return printable.toString();
  }
}
