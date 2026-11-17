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
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyLoginMethodTest {

  @Test
  void is_named_api_key() {
    ApiKeyLoginMethod method =
        new ApiKeyLoginMethod(mock(ApiKeyAuthenticationFilter.class), "X-API-Key");

    assertThat(method.name()).isEqualTo("api-key");
  }

  @Nested
  class Presenting_a_key {

    private final ApiKeyLoginMethod method =
        new ApiKeyLoginMethod(mock(ApiKeyAuthenticationFilter.class), "X-API-Key");

    @AfterEach
    void clear() {
      SecurityContextHolder.clearContext();
    }

    @Test
    void a_request_already_signed_in_still_presents_its_key_but_is_not_carried_by_it() {
      SecurityContextHolder.getContext()
          .setAuthentication(new TestingAuthenticationToken("session", null, "ROLE_X"));
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("X-API-Key", "who_anything");

      assertThat(method.presentsCredential().matches(request)).isTrue();
      assertThat(method.carriesCredential().matches(request)).isFalse();
    }

    @Test
    void a_blank_header_presents_nothing() {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader("X-API-Key", " ");

      assertThat(method.presentsCredential().matches(request)).isFalse();
    }
  }
}
