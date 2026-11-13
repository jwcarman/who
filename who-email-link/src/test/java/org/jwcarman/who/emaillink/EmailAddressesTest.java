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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailAddressesTest {

  private static String addressOfLength(int length) {
    String domain = "@example.com";
    return "a".repeat(length - domain.length()) + domain;
  }

  @Test
  void an_address_of_exactly_320_characters_is_safe() {
    assertThat(EmailAddresses.isSafe(addressOfLength(320))).isTrue();
  }

  @Test
  void an_address_of_321_characters_is_not_safe() {
    assertThat(EmailAddresses.isSafe(addressOfLength(321))).isFalse();
  }

  @Test
  void null_blank_and_control_characters_are_not_safe() {
    assertThat(EmailAddresses.isSafe(null)).isFalse();
    assertThat(EmailAddresses.isSafe("  ")).isFalse();
    assertThat(EmailAddresses.isSafe("owner\u0000@example.com")).isFalse();
  }
}
