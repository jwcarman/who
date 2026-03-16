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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Utility methods for computing message digests. */
public final class MessageDigests {

  private static final String SHA_256 = "SHA-256";
  private static final HexFormat HEX = HexFormat.of();

  private MessageDigests() {
    // utility class
  }

  /**
   * Computes the SHA-256 digest of the given string (UTF-8 encoded) and returns it as a lowercase
   * hex string.
   *
   * @param input the input string
   * @return 64-character lowercase hex string of the SHA-256 digest
   */
  public static String sha256Hex(String input) {
    return HEX.formatHex(messageDigest(SHA_256, input.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Computes the digest of the given bytes using the specified algorithm.
   *
   * @param algorithm the digest algorithm name (e.g. {@code "SHA-256"})
   * @param bytes the input bytes
   * @return the digest bytes
   * @throws IllegalArgumentException if the algorithm is not available
   */
  public static byte[] messageDigest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalArgumentException("Digest algorithm not available: " + algorithm, e);
    }
  }
}
