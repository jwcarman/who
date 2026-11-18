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
package org.jwcarman.who.spring.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LogValuesTest {

  @Test
  void line_breaks_cannot_forge_a_log_line() {
    assertThat(LogValues.printable("/who/me\r\n12:00 ERROR forged"))
        .isEqualTo("/who/me\\r\\n12:00 ERROR forged");
  }

  @Test
  void other_control_characters_are_escaped_too() {
    assertThat(LogValues.printable("a\tb\u0000c\u001b[31m"))
        .isEqualTo("a\\u0009b\\u0000c\\u001b[31m");
  }

  @Test
  void an_ordinary_path_is_unchanged() {
    assertThat(LogValues.printable("/who/me/sessions/%0d%0a?x=é"))
        .isEqualTo("/who/me/sessions/%0d%0a?x=é");
  }
}
