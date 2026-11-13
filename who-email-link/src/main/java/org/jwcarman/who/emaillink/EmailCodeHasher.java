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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.jwcarman.who.core.crypto.Hmacs;

/**
 * Hashes sign-in codes with a keyed HMAC-SHA256, bound to the credential they were issued for. A
 * code has little entropy, so a plain hash would be brute-forceable from a leaked database; the
 * server-side secret prevents that.
 */
public final class EmailCodeHasher {

  private final byte[] key;

  /**
   * Creates a hasher.
   *
   * @param secret the server-side secret; its UTF-8 bytes are the HMAC key
   */
  public EmailCodeHasher(String secret) {
    this.key = requireNonNull(secret, "secret").getBytes(UTF_8);
  }

  /**
   * Hashes a normalized code for a credential.
   *
   * @param credentialId the credential the code was issued for
   * @param normalizedCode the normalized code
   * @return lowercase hex HMAC-SHA256 of {@code credentialId + ":" + normalizedCode}
   */
  public String hash(UUID credentialId, String normalizedCode) {
    return Hmacs.hmacSha256Hex(key, credentialId + ":" + normalizedCode);
  }
}
