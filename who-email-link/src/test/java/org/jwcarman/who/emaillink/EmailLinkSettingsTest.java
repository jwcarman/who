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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailLinkSettingsTest {

  @Test
  void to_string_hides_the_secret() {
    String secret = "0123456789abcdef0123456789abcdef";
    EmailLinkSettings settings =
        new EmailLinkSettings(
            "https://app.test/signin",
            Duration.ofMinutes(15),
            Duration.ofSeconds(60),
            6,
            5,
            secret);

    assertThat(settings.toString()).doesNotContain(secret).contains("codeSecret=****");
  }

  private static EmailLinkSettings settings(int codeLength, int codeMaxAttempts, String secret) {
    return new EmailLinkSettings(
        "https://app.test/signin",
        Duration.ofMinutes(15),
        Duration.ofSeconds(60),
        codeLength,
        codeMaxAttempts,
        secret);
  }

  @Test
  void a_code_length_below_six_is_rejected() {
    assertThatThrownBy(() -> settings(5, 5, "0123456789abcdef0123456789abcdef"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codeLength");
  }

  @Test
  void a_max_attempts_below_one_is_rejected() {
    assertThatThrownBy(() -> settings(6, 0, "0123456789abcdef0123456789abcdef"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codeMaxAttempts");
  }

  @Test
  void a_blank_secret_is_rejected() {
    assertThatThrownBy(() -> settings(6, 5, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codeSecret");
  }

  @Test
  void a_secret_of_31_characters_is_rejected() {
    assertThatThrownBy(() -> settings(6, 5, "0123456789abcdef0123456789abcde"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("codeSecret");
  }

  @Test
  void a_secret_of_32_characters_is_accepted() {
    assertThat(settings(6, 5, "0123456789abcdef0123456789abcdef").codeSecret()).hasSize(32);
  }
}
