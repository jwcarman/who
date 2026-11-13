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
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Utility methods for computing keyed message authentication codes. */
public final class Hmacs {

  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final HexFormat HEX = HexFormat.of();

  private Hmacs() {
    // utility class
  }

  /**
   * Computes the HMAC-SHA256 of the given string (UTF-8 encoded) under the given key and returns it
   * as a lowercase hex string.
   *
   * @param key the secret key
   * @param input the input string
   * @return 64-character lowercase hex string of the HMAC-SHA256
   * @throws IllegalArgumentException if the key is unusable or HMAC-SHA256 is not available
   */
  public static String hmacSha256Hex(byte[] key, String input) {
    try {
      var mac = Mac.getInstance(HMAC_SHA_256);
      mac.init(new SecretKeySpec(key, HMAC_SHA_256));
      return HEX.formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("HMAC-SHA256 failed", e);
    }
  }
}
