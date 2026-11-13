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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.crypto.Hmacs;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailCodeHasherTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private final UUID id = UUID.randomUUID();

  @Test
  void the_same_inputs_give_the_same_64_character_hex() {
    var hasher = new EmailCodeHasher(SECRET);

    var hash = hasher.hash(id, "K7F2QM");

    assertThat(hash).hasSize(64).isEqualTo(hasher.hash(id, "K7F2QM"));
  }

  @Test
  void a_different_credential_id_gives_a_different_hash() {
    var hasher = new EmailCodeHasher(SECRET);

    assertThat(hasher.hash(id, "K7F2QM")).isNotEqualTo(hasher.hash(UUID.randomUUID(), "K7F2QM"));
  }

  @Test
  void a_different_secret_gives_a_different_hash() {
    var other = new EmailCodeHasher(SECRET + "x");

    assertThat(new EmailCodeHasher(SECRET).hash(id, "K7F2QM"))
        .isNotEqualTo(other.hash(id, "K7F2QM"));
  }

  @Test
  void the_hash_is_the_hmac_of_the_id_and_code() {
    var expected = Hmacs.hmacSha256Hex(SECRET.getBytes(UTF_8), id + ":" + "K7F2QM");

    assertThat(new EmailCodeHasher(SECRET).hash(id, "K7F2QM")).isEqualTo(expected);
  }
}
