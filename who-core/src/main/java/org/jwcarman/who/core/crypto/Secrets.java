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
package org.jwcarman.who.core.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates secret tokens. This is the only source of secret tokens in who. */
public final class Secrets {

  private static final int TOKEN_BYTES = 32;
  private static final String CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private Secrets() {
    // utility class
  }

  /**
   * Generates a new secret token: 32 bytes from a {@link SecureRandom}, encoded as 43 URL-safe
   * characters without padding.
   *
   * @return a new secret token
   */
  public static String token() {
    var bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /**
   * Generates a new code of the given length. Each character is drawn uniformly from the Crockford
   * base-32 alphabet, which omits {@code I}, {@code L}, {@code O} and {@code U}.
   *
   * @param length the number of characters, at least 1
   * @return a new code
   * @throws IllegalArgumentException if {@code length} is less than 1
   */
  public static String code(int length) {
    if (length < 1) {
      throw new IllegalArgumentException("Code length must be at least 1: " + length);
    }
    var code = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
    }
    return code.toString();
  }
}
