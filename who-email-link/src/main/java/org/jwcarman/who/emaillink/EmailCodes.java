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
package org.jwcarman.who.emaillink;

import java.util.Locale;
import java.util.Optional;

/** Normalizes user-typed sign-in codes and formats generated ones for display. */
final class EmailCodes {

  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private EmailCodes() {
    // utility class
  }

  /**
   * Normalizes a typed code: upper-cases it, drops spaces and dashes, and maps the look-alike
   * letters {@code O} to {@code 0} and {@code I}/{@code L} to {@code 1}.
   *
   * @param raw the typed code, possibly {@code null}
   * @param length the expected code length
   * @return the normalized code, or empty if {@code raw} is {@code null}, has the wrong length, or
   *     contains a character outside the code alphabet
   */
  static Optional<String> normalize(String raw, int length) {
    if (raw == null) {
      return Optional.empty();
    }
    var normalized = new StringBuilder(raw.length());
    for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
      if (c == ' ' || c == '-') {
        continue;
      }
      normalized.append(
          switch (c) {
            case 'O' -> '0';
            case 'I', 'L' -> '1';
            default -> c;
          });
    }
    if (normalized.length() != length || !allInAlphabet(normalized)) {
      return Optional.empty();
    }
    return Optional.of(normalized.toString());
  }

  /**
   * Splits a code in two with a dash for display; the first group is the longer when the length is
   * odd.
   *
   * @param code the normalized code
   * @return the display form, for example {@code K7F-2QM}
   */
  static String format(String code) {
    int split = (code.length() + 1) / 2;
    return code.substring(0, split) + "-" + code.substring(split);
  }

  private static boolean allInAlphabet(CharSequence s) {
    return s.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
  }
}
