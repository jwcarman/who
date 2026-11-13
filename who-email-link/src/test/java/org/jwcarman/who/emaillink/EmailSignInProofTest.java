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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailSignInProofTest {

  @Test
  void a_link_token_without_a_token_is_rejected() {
    assertThatThrownBy(() -> new LinkToken(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void an_email_code_without_an_address_is_rejected() {
    assertThatThrownBy(() -> new EmailCode(null, "K7F2QM"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void an_email_code_without_a_code_is_rejected() {
    assertThatThrownBy(() -> new EmailCode("a@b.c", null)).isInstanceOf(NullPointerException.class);
  }
}
