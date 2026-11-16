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
package org.jwcarman.who.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoPathsTest {

  @Test
  void defaults_root_is_who() {
    assertThat(WhoPaths.defaults().root()).isEqualTo("/who");
    assertThat(WhoPaths.defaults().of("/me")).isEqualTo("/who/me");
  }

  @Test
  void a_custom_root_prefixes_every_path() {
    assertThat(new WhoPaths("/auth").of("/passkey/sign-in")).isEqualTo("/auth/passkey/sign-in");
  }

  @Test
  void a_nested_root_is_allowed() {
    assertThat(new WhoPaths("/api/who").of("/me")).isEqualTo("/api/who/me");
  }

  @ParameterizedTest
  @ValueSource(strings = {"who", "/who/", "/", "", "/w*", "/{x}", "/a?b", "/a#b", "/a;b", "/a b"})
  void rejects_a_root(String root) {
    assertThatThrownBy(() -> new WhoPaths(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("\"" + root + "\"");
  }

  @Test
  void a_relative_path_must_start_with_a_slash() {
    WhoPaths paths = WhoPaths.defaults();
    assertThatThrownBy(() -> paths.of("me")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equal_roots_are_equal() {
    assertThat(new WhoPaths("/auth")).isEqualTo(new WhoPaths("/auth")).hasToString("/auth");
    assertThat(new WhoPaths("/auth")).hasSameHashCodeAs(new WhoPaths("/auth"));
  }
}
