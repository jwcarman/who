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
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;

import tools.jackson.databind.json.JsonMapper;

class JsonGenerateOneTimeTokenRequestResolverTest {

  private final JsonGenerateOneTimeTokenRequestResolver resolver =
      new JsonGenerateOneTimeTokenRequestResolver(JsonMapper.builder().build());

  private static MockHttpServletRequest post(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ott/generate");
    request.setContentType("application/json");
    request.setContent(body.getBytes(UTF_8));
    return request;
  }

  @Test
  void readsEmail() {
    GenerateOneTimeTokenRequest result =
        resolver.resolve(post("{\"email\": \"owner@example.com\"}"));

    assertThat(result.getUsername()).isEqualTo("owner@example.com");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{",
        "{}",
        "{\"email\": 5}",
        "",
        "{\"email\": \"\"}",
        "{\"email\": \"   \"}",
        "[]",
        "null",
        "email=owner@example.com"
      })
  void malformedBodiesGiveTheNoAddressUsername(String body) {
    GenerateOneTimeTokenRequest result = resolver.resolve(post(body));

    assertThat(result).isNotNull();
    assertThat(result.getUsername()).isEqualTo(JsonGenerateOneTimeTokenRequestResolver.NO_ADDRESS);
  }

  @Test
  void requestWithNoBodyGivesTheNoAddressUsername() {
    GenerateOneTimeTokenRequest result =
        resolver.resolve(new MockHttpServletRequest("POST", "/ott/generate"));

    assertThat(result.getUsername()).isEqualTo(JsonGenerateOneTimeTokenRequestResolver.NO_ADDRESS);
  }
}
