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
import java.util.Optional;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StatelessAnswersTest {

  private static final AuthenticationException SIGNED_OUT =
      new InsufficientAuthenticationException("signed out");

  @Test
  void two_methods_with_one_scheme_name_it_once() throws Exception {
    MockHttpServletResponse response = answer(List.of(bearer(), bearer()), response());

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
  }

  @Test
  void an_error_page_coming_back_through_adds_nothing_twice() throws Exception {
    MockHttpServletResponse response = response();
    response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

    answer(List.of(bearer()), response);

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer");
  }

  @Test
  void a_bearer_challenge_with_parameters_already_names_the_scheme() throws Exception {
    MockHttpServletResponse response = response();
    response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "bearer error=\"invalid_token\"");

    answer(List.of(bearer()), response);

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE))
        .containsExactly("bearer error=\"invalid_token\"");
  }

  @Test
  void a_challenge_with_parameters_coming_back_through_is_not_added_twice() throws Exception {
    MockHttpServletResponse response = response();

    answer(List.of(bearer("Bearer realm=\"api\"")), response);
    answer(List.of(bearer("Bearer realm=\"api\"")), response);

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE))
        .containsExactly("Bearer realm=\"api\"");
  }

  @Test
  void a_scheme_that_only_starts_like_who_s_keeps_who_s_own() throws Exception {
    MockHttpServletResponse response = response();
    response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "BearerToken");

    answer(List.of(bearer()), response);

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE))
        .containsExactly("BearerToken", "Bearer");
  }

  @Test
  void a_scheme_set_by_something_else_keeps_who_s_own() throws Exception {
    MockHttpServletResponse response = response();
    response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"other\"");

    answer(List.of(bearer()), response);

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE))
        .containsExactly("Basic realm=\"other\"", "Bearer");
  }

  @Test
  void a_signed_out_request_is_401() throws Exception {
    assertThat(answer(List.of(bearer()), response()).getStatus()).isEqualTo(401);
  }

  private static MockHttpServletResponse answer(
      List<StatelessLoginMethod> methods, MockHttpServletResponse response) throws Exception {
    AuthenticationEntryPoint entryPoint =
        StatelessAnswers.entryPoint(new WhoExceptionHandoff(null), methods);
    entryPoint.commence(new MockHttpServletRequest(), response, SIGNED_OUT);
    return response;
  }

  private static MockHttpServletResponse response() {
    return new MockHttpServletResponse();
  }

  private static StatelessLoginMethod bearer() {
    return bearer("Bearer");
  }

  private static StatelessLoginMethod bearer(String challenge) {
    return new StatelessLoginMethod() {
      @Override
      public void configure(HttpSecurity http) {
        // never mounted: only the challenge is read
      }

      @Override
      public RequestMatcher carriesCredential() {
        return AnyRequestMatcher.INSTANCE;
      }

      @Override
      public Optional<String> challenge() {
        return Optional.of(challenge);
      }
    };
  }
}
