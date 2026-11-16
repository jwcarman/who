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
class EmailAddressChangedMessageTest {

  @Test
  void the_new_address_is_masked_but_its_domain_shown() {
    assertThat(
            new EmailAddressChangedMessage("old@example.com", "new@example.com").maskedNewAddress())
        .isEqualTo("n***@example.com");
  }

  @Test
  void an_address_without_a_domain_shows_only_its_first_character() {
    assertThat(new EmailAddressChangedMessage("old@example.com", "nobody").maskedNewAddress())
        .isEqualTo("n***");
  }
}
