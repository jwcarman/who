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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoPublicPathsTest {

  private static PasswordlessLoginMethod method(String... paths) {
    return new PasswordlessLoginMethod() {
      @Override
      public void configure(HttpSecurity http) {
        // only the public paths matter here
      }

      @Override
      public Set<String> publicPaths() {
        return Set.of(paths);
      }
    };
  }

  @Test
  void always_includes_the_error_page() {
    assertThat(new WhoPublicPaths(List.of(), List.of()).patterns()).containsExactly("/error");
  }

  @Test
  void adds_each_methods_paths_then_the_configured_ones() {
    assertThat(
            new WhoPublicPaths(
                    List.of(method("/who/email/sign-in")), List.of("/signin", "/assets/**"))
                .patterns())
        .containsExactly("/error", "/who/email/sign-in", "/signin", "/assets/**");
  }

  @Test
  void drops_duplicates() {
    assertThat(
            new WhoPublicPaths(List.of(method("/error"), method("/a")), List.of("/a", "/b"))
                .patterns())
        .containsExactly("/error", "/a", "/b");
  }

  @Test
  void a_login_method_has_no_public_paths_unless_it_says_so() {
    PasswordlessLoginMethod method = http -> {};
    assertThat(method.publicPaths()).isEmpty();
  }
}
