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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SignInOptionsFilterTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);

  private static PasswordlessLoginMethod method(Map<String, Object> options) {
    return new PasswordlessLoginMethod() {
      @Override
      public void configure(HttpSecurity http) {}

      @Override
      public Map<String, Object> signInOptions() {
        return options;
      }
    };
  }

  private MockHttpServletResponse get(
      String path, WhoPaths paths, List<PasswordlessLoginMethod> methods) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SignInOptionsFilter(paths, methods, json)
        .doFilter(new MockHttpServletRequest("GET", path), response, chain);
    return response;
  }

  @Nested
  class Options {

    @Test
    void sign_in_options_are_public() throws Exception {
      // no authentication anywhere, and nothing but the methods' own words in the answer
      SecurityContextHolder.clearContext();

      MockHttpServletResponse response =
          get(
              "/who/sign-in",
              WhoPaths.defaults(),
              List.of(method(Map.of("email", true)), method(Map.of("passkey", true))));

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith("application/json");
      assertThat(json.readTree(response.getContentAsString()))
          .isEqualTo(json.readTree("{\"email\":true,\"passkey\":true,\"providers\":[]}"));
      assertThat(reachedChain).isFalse();
    }

    @Test
    void a_method_that_is_absent_is_off() throws Exception {
      MockHttpServletResponse response = get("/who/sign-in", WhoPaths.defaults(), List.of());

      assertThat(json.readTree(response.getContentAsString()))
          .isEqualTo(json.readTree("{\"email\":false,\"passkey\":false,\"providers\":[]}"));
    }

    @Test
    void sign_in_options_list_providers_by_registration() throws Exception {
      Map<String, Object> oidc =
          Map.of(
              "providers",
              List.of(
                  Map.of("id", "github", "name", "GitHub"),
                  Map.of("id", "okta", "name", "Acme SSO")));

      MockHttpServletResponse response =
          get(
              "/who/sign-in",
              WhoPaths.defaults(),
              List.of(method(Map.of("email", true)), method(oidc)));

      assertThat(json.readTree(response.getContentAsString()))
          .isEqualTo(
              json.readTree(
                  """
                  {"email":true,"passkey":false,
                   "providers":[{"id":"github","name":"GitHub"},{"id":"okta","name":"Acme SSO"}]}
                  """));
    }

    @Test
    void it_answers_under_the_configured_root() throws Exception {
      WhoPaths paths = new WhoPaths("/auth");

      assertThat(get("/auth/sign-in", paths, List.of()).getStatus()).isEqualTo(200);
      assertThat(reachedChain).isFalse();
      get("/who/sign-in", paths, List.of());
      assertThat(reachedChain).isTrue();
    }

    @Test
    void only_a_get_is_answered() throws Exception {
      MockHttpServletResponse response = new MockHttpServletResponse();

      new SignInOptionsFilter(WhoPaths.defaults(), List.of(), json)
          .doFilter(new MockHttpServletRequest("POST", "/who/sign-in"), response, chain);

      assertThat(reachedChain).isTrue();
    }
  }
}
