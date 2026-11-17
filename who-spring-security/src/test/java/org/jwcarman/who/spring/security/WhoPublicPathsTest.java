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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
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
    assertThat(new WhoPublicPaths(WhoPaths.defaults(), List.of(), List.of(), List.of()).patterns())
        .startsWith("/error");
  }

  @Test
  void adds_each_methods_paths_then_the_configured_ones() {
    assertThat(
            new WhoPublicPaths(
                    WhoPaths.defaults(),
                    List.of(method("/who/email/sign-in")),
                    List.of("/signin", "/assets/**"),
                    List.of())
                .patterns())
        .containsExactly("/error", "/who/sign-in", "/who/email/sign-in", "/signin", "/assets/**");
  }

  @Test
  void drops_duplicates() {
    assertThat(
            new WhoPublicPaths(
                    WhoPaths.defaults(),
                    List.of(method("/error"), method("/a")),
                    List.of("/a", "/b"),
                    List.of())
                .patterns())
        .containsExactly("/error", "/who/sign-in", "/a", "/b");
  }

  @Test
  void the_sign_in_options_follow_the_root() {
    assertThat(
            new WhoPublicPaths(new WhoPaths("/auth"), List.of(), List.of(), List.of()).patterns())
        .containsExactly("/error", "/auth/sign-in");
  }

  @Test
  void a_login_method_has_no_public_paths_unless_it_says_so() {
    PasswordlessLoginMethod method = http -> {};
    assertThat(method.publicPaths()).isEmpty();
  }

  @Nested
  class Validation {

    @Test
    void a_blank_contributed_pattern_is_refused() {
      WhoPaths paths = WhoPaths.defaults();
      List<String> contributed = List.of("/a", " ");

      assertThatThrownBy(() -> new WhoPublicPaths(paths, List.of(), List.of(), contributed))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("contributed");
    }

    @Test
    void a_null_configured_pattern_is_refused() {
      WhoPaths paths = WhoPaths.defaults();
      List<String> configured = Arrays.asList("/a", null);

      assertThatThrownBy(() -> new WhoPublicPaths(paths, List.of(), configured, List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("configured");
    }
  }

  @Nested
  class Contributed {

    @Test
    void contributed_paths_join_the_property_and_the_sign_in_paths() {
      assertThat(
              new WhoPublicPaths(
                      WhoPaths.defaults(),
                      List.of(method("/who/email/sign-in")),
                      List.of("/signin"),
                      List.of("/index.html", "/assets/**"))
                  .patterns())
          .containsExactly(
              "/error",
              "/who/sign-in",
              "/who/email/sign-in",
              "/signin",
              "/index.html",
              "/assets/**");
    }

    @Test
    void two_contributors_compose() {
      WhoPublicPathContributor first = () -> List.of("/index.html");
      WhoPublicPathContributor second = () -> List.of("/assets/**", "/index.html");
      assertThat(
              new WhoPublicPaths(
                      WhoPaths.defaults(),
                      List.of(),
                      List.of(),
                      Stream.of(first, second)
                          .flatMap(contributor -> contributor.publicPaths().stream())
                          .toList())
                  .patterns())
          .containsExactly("/error", "/who/sign-in", "/index.html", "/assets/**");
    }

    @Test
    void a_contributed_path_never_displaces_an_earlier_one() {
      assertThat(
              new WhoPublicPaths(
                      WhoPaths.defaults(), List.of(), List.of("/b", "/a"), List.of("/a", "/c"))
                  .patterns())
          .containsExactly("/error", "/who/sign-in", "/b", "/a", "/c");
    }
  }
}
