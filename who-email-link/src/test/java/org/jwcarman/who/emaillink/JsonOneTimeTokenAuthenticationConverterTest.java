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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.core.Authentication;

import tools.jackson.databind.json.JsonMapper;

class JsonOneTimeTokenAuthenticationConverterTest {

  private final JsonOneTimeTokenAuthenticationConverter converter =
      new JsonOneTimeTokenAuthenticationConverter(JsonMapper.builder().build());

  private static MockHttpServletRequest post(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/ott");
    request.setContentType("application/json");
    request.setContent(body.getBytes(UTF_8));
    return request;
  }

  @Test
  void readsToken() {
    Authentication result = converter.convert(post("{\"token\": \"abc\"}"));

    assertThat(result).isInstanceOf(OneTimeTokenAuthenticationToken.class);
    assertThat(((OneTimeTokenAuthenticationToken) result).getTokenValue()).isEqualTo("abc");
    assertThat(result.isAuthenticated()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{", "{}", "{\"token\": 5}", "", "{\"token\": \"\"}", "[]", "null", "token=abc"})
  void malformedGivesNull(String body) {
    assertThat(converter.convert(post(body))).isNull();
  }
}
