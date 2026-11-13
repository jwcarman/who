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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.core.Authentication;

import com.code_intelligence.jazzer.junit.FuzzTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fuzzes the JSON bodies of the two unauthenticated sign-in endpoints, {@code /login/ott} and
 * {@code /ott/generate}. Runs as a quick regression test in every build; {@code JAZZER_FUZZ=1}
 * generates new inputs.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SignInRequestFuzzTest {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private static final JsonOneTimeTokenAuthenticationConverter CONVERTER =
      new JsonOneTimeTokenAuthenticationConverter(JSON_MAPPER);

  private static final JsonGenerateOneTimeTokenRequestResolver RESOLVER =
      new JsonGenerateOneTimeTokenRequestResolver(JSON_MAPPER);

  private static MockHttpServletRequest post(String path, byte[] body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setContentType("application/json");
    request.setContent(body);
    return request;
  }

  @FuzzTest(maxDuration = "2m")
  void login_body_becomes_one_well_formed_proof(byte[] body) {
    Authentication result = CONVERTER.convert(post("/login/ott", body));

    assertThat(result).isInstanceOf(EmailSignInAuthenticationToken.class);
    assertThat(result.isAuthenticated()).isFalse();
    EmailSignInProof proof = ((EmailSignInAuthenticationToken) result).proof();
    switch (proof) {
      case LinkToken(String token) -> assertThat(token).isNotBlank();
      case EmailCode(String address, String code) -> {
        assertThat(address).isNotBlank();
        assertThat(code).isNotBlank();
      }
      case Malformed() -> {
        // anything else is malformed, which is always a safe answer
      }
    }
    if (body.length > JsonBodies.MAX_BODY_BYTES) {
      assertThat(proof).isInstanceOf(Malformed.class);
    }
  }

  @FuzzTest(maxDuration = "2m")
  void generate_body_becomes_an_address_or_the_no_address_marker(byte[] body) {
    GenerateOneTimeTokenRequest result = RESOLVER.resolve(post("/ott/generate", body));

    String username = result.getUsername();
    assertThat(username).isNotBlank();
    if (body.length > JsonBodies.MAX_BODY_BYTES) {
      assertThat(username).isEqualTo(JsonGenerateOneTimeTokenRequestResolver.NO_ADDRESS);
    }
    // whatever the body held, only a bounded address without control characters is looked up
    if (EmailAddresses.isSafe(username)) {
      assertThat(username.length()).isLessThanOrEqualTo(EmailAddresses.MAX_LENGTH);
      assertThat(username.chars()).noneMatch(Character::isISOControl);
    }
  }
}
