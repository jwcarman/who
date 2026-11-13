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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailCodesTest {

  @Nested
  class Normalize {

    @Test
    void case_spaces_and_dashes_are_ignored() {
      assertThat(EmailCodes.normalize("k7f-2qm", 6)).contains("K7F2QM");
      assertThat(EmailCodes.normalize(" K7F 2QM ", 6)).contains("K7F2QM");
      assertThat(EmailCodes.normalize("K7F2QM", 6)).contains("K7F2QM");
    }

    @Test
    void look_alike_letters_are_mapped_to_digits() {
      assertThat(EmailCodes.normalize("OIL234", 6)).contains("011234");
    }

    @Test
    void a_character_outside_the_alphabet_is_rejected() {
      assertThat(EmailCodes.normalize("K7F2QU", 6)).isEmpty();
    }

    @Test
    void the_wrong_length_is_rejected() {
      assertThat(EmailCodes.normalize("K7F2Q", 6)).isEmpty();
      assertThat(EmailCodes.normalize("K7F2QMM", 6)).isEmpty();
    }

    @Test
    void an_input_longer_than_64_characters_is_rejected() {
      assertThat(EmailCodes.normalize("K7F2QM" + " ".repeat(59), 6)).isEmpty();
    }

    @Test
    void null_is_rejected() {
      assertThat(EmailCodes.normalize(null, 6)).isEmpty();
    }
  }

  @Nested
  class Format {

    @Test
    void an_even_length_code_is_split_in_half() {
      assertThat(EmailCodes.format("K7F2QM")).isEqualTo("K7F-2QM");
    }

    @Test
    void an_odd_length_code_has_the_longer_group_first() {
      assertThat(EmailCodes.format("ABCDEFG")).isEqualTo("ABCD-EFG");
    }
  }
}
