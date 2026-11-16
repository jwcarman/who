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
package org.jwcarman.who.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyFormatTest {

  private final ApiKeyFormat format = new ApiKeyFormat(ApiKeyFormat.DEFAULT_PREFIX);

  @Test
  void the_default_prefix_is_who() {
    assertThat(ApiKeyFormat.DEFAULT_PREFIX).isEqualTo("who_");
  }

  @Test
  void a_generated_key_starts_with_the_prefix_and_looks_valid() {
    String key = format.generate();

    assertThat(key).startsWith("who_");
    assertThat(format.looksValid(key)).isTrue();
  }

  @Test
  void a_generated_key_is_prefix_plus_36_base62_characters() {
    assertThat(new ApiKeyFormat("acme_live_").generate()).matches("acme_live_[0-9A-Za-z]{36}");
  }

  @Test
  void a_changed_character_fails_the_checksum() {
    String key = format.generate();
    int at = key.length() - 10;
    char swapped = key.charAt(at) == 'a' ? 'b' : 'a';
    String changed = key.substring(0, at) + swapped + key.substring(at + 1);

    assertThat(format.looksValid(changed)).isFalse();
  }

  @Test
  void a_key_made_under_another_prefix_still_looks_valid() {
    String old = new ApiKeyFormat("old_").generate();

    assertThat(format.looksValid(old)).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "who_",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "who_0123456789ABCDEFGHIJabcdefghij012345",
        "who_------------------------------------"
      })
  void text_that_is_not_a_key_does_not_look_valid(String text) {
    assertThat(format.looksValid(text)).isFalse();
  }

  @Test
  void two_keys_differ() {
    assertThat(format.generate()).isNotEqualTo(format.generate());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"a", "who", "who-", "w h_", "abcdefghijklmnopqrst_"})
  void a_bad_prefix_is_refused(String prefix) {
    assertThatThrownBy(() -> new ApiKeyFormat(prefix))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix");
  }

  @Test
  void a_two_character_prefix_is_allowed() {
    assertThat(new ApiKeyFormat("k_").generate()).startsWith("k_");
  }
}
