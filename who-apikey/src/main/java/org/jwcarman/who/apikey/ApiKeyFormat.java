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
package org.jwcarman.who.apikey;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * The shape of a who API key: a prefix, 30 random base62 characters and a 6-character base62
 * checksum, the shape GitHub uses for its tokens.
 *
 * <p>The prefix lets a secret scanner recognize a leaked key, and the checksum lets it, and who,
 * tell a key from text that only looks like one without looking anything up. The checksum is not a
 * security check: who signs a key in by the hash of the whole key. A key made under an earlier
 * prefix still has a valid shape, so changing the prefix leaves existing keys working.
 */
public final class ApiKeyFormat {

  /** The prefix who uses unless configured otherwise. */
  public static final String DEFAULT_PREFIX = "who_";

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int RANDOM_LENGTH = 30;
  private static final int CHECKSUM_LENGTH = 6;
  private static final int TAIL_LENGTH = RANDOM_LENGTH + CHECKSUM_LENGTH;
  private static final Pattern PREFIX_SHAPE = Pattern.compile("\\w{1,19}_");
  private static final Pattern TAIL = Pattern.compile("[0-9A-Za-z]{" + TAIL_LENGTH + "}");
  // the largest multiple of 62 a byte reaches; a byte at or above it is drawn again, so every
  // character is equally likely
  private static final int UNBIASED_LIMIT = 256 - (256 % ALPHABET.length());
  private static final SecureRandom RANDOM = new SecureRandom();

  private final String prefix;

  /**
   * Creates the format for new keys.
   *
   * @param prefix starts every new key: letters, digits and {@code _}, 2 to 20 characters, ending
   *     in {@code _}
   * @throws IllegalArgumentException if the prefix breaks those rules
   */
  public ApiKeyFormat(String prefix) {
    if (prefix == null || !PREFIX_SHAPE.matcher(prefix).matches()) {
      throw new IllegalArgumentException(
          "An API key prefix must be 2 to 20 letters, digits or underscores, ending in an"
              + " underscore, such as \"who_\"; got "
              + (prefix == null ? "none" : "\"" + prefix + "\""));
    }
    this.prefix = prefix;
  }

  /**
   * Generates a new key.
   *
   * @return the prefix, 30 random characters and their checksum
   */
  public String generate() {
    StringBuilder random = new StringBuilder(RANDOM_LENGTH);
    byte[] bytes = new byte[1];
    while (random.length() < RANDOM_LENGTH) {
      RANDOM.nextBytes(bytes);
      int value = bytes[0] & 0xff;
      if (value < UNBIASED_LIMIT) {
        random.append(ALPHABET.charAt(value % ALPHABET.length()));
      }
    }
    String body = prefix + random;
    return body + checksum(body);
  }

  /**
   * Whether text has the shape of a who key: a valid prefix (this one or any other), the random
   * part, and a checksum that matches. It says nothing about whether the key exists.
   *
   * @param key the text to check
   * @return {@code true} when it could be a who key
   */
  public boolean looksValid(String key) {
    if (key == null || key.length() <= TAIL_LENGTH) {
      return false;
    }
    int tailStart = key.length() - TAIL_LENGTH;
    if (!PREFIX_SHAPE.matcher(key.substring(0, tailStart)).matches()
        || !TAIL.matcher(key.substring(tailStart)).matches()) {
      return false;
    }
    int checksumStart = key.length() - CHECKSUM_LENGTH;
    return checksum(key.substring(0, checksumStart)).equals(key.substring(checksumStart));
  }

  private static String checksum(String body) {
    CRC32 crc = new CRC32();
    crc.update(body.getBytes(StandardCharsets.US_ASCII));
    long value = crc.getValue();
    char[] digits = new char[CHECKSUM_LENGTH];
    for (int i = CHECKSUM_LENGTH - 1; i >= 0; i--) {
      digits[i] = ALPHABET.charAt((int) (value % ALPHABET.length()));
      value /= ALPHABET.length();
    }
    return new String(digits);
  }
}
