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
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;
import org.springframework.mock.web.MockHttpServletRequest;
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

  private static EmailSignInProof proofOf(Authentication result) {
    assertThat(result).isInstanceOf(EmailSignInAuthenticationToken.class);
    assertThat(result.isAuthenticated()).isFalse();
    return ((EmailSignInAuthenticationToken) result).proof();
  }

  @Test
  void readsToken() {
    assertThat(proofOf(converter.convert(post("{\"token\":\"t\"}")))).isEqualTo(new LinkToken("t"));
  }

  @Test
  void readsEmailAndCodeAsTyped() {
    assertThat(proofOf(converter.convert(post("{\"email\":\"a@b.c\",\"code\":\"k7f-2qm\"}"))))
        .isEqualTo(new EmailCode("a@b.c", "k7f-2qm"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"token\":\"t\",\"email\":\"a@b.c\",\"code\":\"x\"}",
        "{\"token\":\"t\",\"email\":\"a@b.c\"}",
        "{\"token\":\"t\",\"code\":\"x\"}",
        "{\"email\":\"a@b.c\",\"code\":123456}",
        "{\"email\":null,\"code\":\"K7F2QM\"}",
        "{\"email\":\"a@b.c\"}",
        "{\"code\":\"K7F2QM\"}",
        "{\"email\":\" \",\"code\":\"K7F2QM\"}",
        "{\"email\":\"a@b.c\",\"code\":\"\"}",
        "{",
        "{}",
        "{\"token\": 5}",
        "",
        "{\"token\": \"\"}",
        "{\"token\": \"  \"}",
        "[]",
        "null",
        "token=abc"
      })
  void malformedNeverGivesNull(String body) {
    assertThat(proofOf(converter.convert(post(body)))).isEqualTo(new Malformed());
  }

  @Test
  void requestWithNoBodyNeverGivesNull() {
    assertThat(proofOf(converter.convert(new MockHttpServletRequest("POST", "/login/ott"))))
        .isEqualTo(new Malformed());
  }
}
