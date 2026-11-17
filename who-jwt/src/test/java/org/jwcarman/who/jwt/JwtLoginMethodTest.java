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
package org.jwcarman.who.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JwtLoginMethodTest {

  @Test
  void the_challenge_is_bearer() {
    JwtLoginMethod method =
        new JwtLoginMethod(
            new WhoJwtAuthenticationConverter(
                mock(FederatedCredentialRepository.class), mock(WhoService.class)),
            mock(JwtDecoder.class));

    assertThat(method.challenge()).contains("Bearer");
  }

  @Test
  void is_named_jwt() {
    assertThat(method(mock(JwtDecoder.class)).name()).isEqualTo("jwt");
  }

  private static JwtLoginMethod method(JwtDecoder decoder) {
    return new JwtLoginMethod(
        new WhoJwtAuthenticationConverter(
            mock(FederatedCredentialRepository.class), mock(WhoService.class)),
        decoder);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer abc", "bearer abc", "BEARER abc"})
  void a_bearer_token_carries_a_credential(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", authorization);

    assertThat(method(mock(JwtDecoder.class)).carriesCredential().matches(request)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer ", "Bearer    ", "Basic abc", "Bearer"})
  void an_empty_or_other_authorization_carries_none(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", authorization);

    assertThat(method(mock(JwtDecoder.class)).carriesCredential().matches(request)).isFalse();
  }

  @Test
  void no_authorization_carries_none() {
    assertThat(
            method(mock(JwtDecoder.class))
                .carriesCredential()
                .matches(new MockHttpServletRequest()))
        .isFalse();
  }

  static final JwtDecoder FIXED = mock(JwtDecoder.class);

  @Configuration
  @EnableWebSecurity
  static class FixedDecoderChain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      method(FIXED).configure(http);
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Test
  void a_method_given_a_decoder_verifies_tokens_with_it() {
    when(FIXED.decode("abc")).thenThrow(new BadJwtException("not a token"));

    new WebApplicationContextRunner()
        .withUserConfiguration(FixedDecoderChain.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              MockMvcBuilders.webAppContextSetup(context)
                  .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                  .build()
                  .perform(get("/anything").header("Authorization", "Bearer abc"))
                  .andExpect(status().isUnauthorized());
            });

    verify(FIXED).decode("abc");
  }

  @Nested
  class Beside_a_session {

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    {
      request.addHeader("Authorization", "Bearer junk");
    }

    @AfterEach
    void clear() {
      SecurityContextHolder.clearContext();
    }

    @Test
    void a_request_already_signed_in_presents_its_token_but_is_not_carried_by_it() {
      SecurityContextHolder.getContext()
          .setAuthentication(new TestingAuthenticationToken("session", null, "ROLE_X"));
      JwtLoginMethod method = method(mock(JwtDecoder.class));

      assertThat(method.presentsCredential().matches(request)).isTrue();
      assertThat(method.carriesCredential().matches(request)).isFalse();
    }

    @Test
    void an_anonymous_request_is_carried_by_its_token() {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new AnonymousAuthenticationToken(
                  "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

      assertThat(method(mock(JwtDecoder.class)).carriesCredential().matches(request)).isTrue();
    }
  }
}
