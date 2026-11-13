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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HmacsTest {

  private static final String INPUT = "what do ya want for nothing?";

  @Nested
  class HmacSha256Hex {

    @Test
    void matches_rfc_4231_test_case_2() {
      var key = "Jefe".getBytes(StandardCharsets.UTF_8);

      assertThat(Hmacs.hmacSha256Hex(key, INPUT))
          .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void a_different_key_gives_a_different_value() {
      var one = Hmacs.hmacSha256Hex("Jefe".getBytes(StandardCharsets.UTF_8), INPUT);
      var other = Hmacs.hmacSha256Hex("Other".getBytes(StandardCharsets.UTF_8), INPUT);

      assertThat(other).isNotEqualTo(one);
    }
  }
}
