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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

class SecretsTest {

  @Test
  void tokenIs43UrlSafeCharacters() {
    assertThat(Secrets.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
  }

  @Test
  void tokensAreDistinct() {
    var seen = new HashSet<String>();
    for (int i = 0; i < 10_000; i++) {
      seen.add(Secrets.token());
    }
    assertThat(seen).hasSize(10_000);
  }

  @Test
  void tokenDecodesToThirtyTwoBytes() {
    assertThat(Base64.getUrlDecoder().decode(Secrets.token())).hasSize(32);
  }

  @Test
  void codeHasTheRequestedLengthAndOnlyCrockfordCharacters() {
    for (int i = 0; i < 1_000; i++) {
      assertThat(Secrets.code(6)).matches("^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{6}$");
    }
  }

  @Test
  void codesDiffer() {
    var seen = new HashSet<String>();
    for (int i = 0; i < 1_000; i++) {
      seen.add(Secrets.code(6));
    }
    assertThat(seen.size()).isGreaterThan(990);
  }

  @Test
  void codeRejectsALengthBelowOne() {
    assertThatThrownBy(() -> Secrets.code(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
