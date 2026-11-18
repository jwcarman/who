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
package org.jwcarman.who.core.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OutgoingEmailTest {

  record Hello(String address) implements WhoEmail {
    @Override
    public String template() {
      return "hello";
    }
  }

  private static final Hello HELLO = new Hello("ann@example.com");

  @Nested
  class Construction {

    @Test
    void html_may_be_null() {
      assertThat(new OutgoingEmail("ann@example.com", "Hi", "text", null, HELLO).html()).isNull();
    }

    @Test
    void the_text_is_required() {
      assertThatThrownBy(() -> new OutgoingEmail("ann@example.com", "Hi", null, null, HELLO))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("text");
    }
  }

  @Nested
  class ToString {

    @Test
    void to_string_hides_the_text_and_html() {
      String shown =
          new OutgoingEmail("ann@example.com", "Hi", "secret-link", "<b>secret</b>", HELLO)
              .toString();

      assertThat(shown)
          .contains("ann@example.com", "Hi", "hello")
          .doesNotContain("secret-link", "<b>secret</b>");
    }
  }
}
