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
package org.jwcarman.who.spring.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonAuthenticationHandlersTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final JsonAuthenticationHandlers handlers = new JsonAuthenticationHandlers(mapper);
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void successBody() throws Exception {
    Identity identity = Identity.create();

    handlers
        .success()
        .onAuthenticationSuccess(
            new MockHttpServletRequest(),
            response,
            new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of())));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith("application/json");
    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(1);
    assertThat(body.get("identityId").asString()).isEqualTo(identity.id().toString());
  }

  @Test
  void failureBody() throws Exception {
    handlers
        .failure()
        .onAuthenticationFailure(
            new MockHttpServletRequest(),
            response,
            new InvalidOneTimeTokenException("invalid_token"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/json");
    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(1);
    assertThat(body.get("error").asString()).isEqualTo("invalid_token");
  }

  @Test
  void failureCarriesTheGivenErrorCode() throws Exception {
    handlers
        .failure("invalid_credential")
        .onAuthenticationFailure(
            new MockHttpServletRequest(),
            response,
            new InvalidOneTimeTokenException("invalid_credential"));

    assertThat(response.getStatus()).isEqualTo(401);
    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(1);
    assertThat(body.get("error").asString()).isEqualTo("invalid_credential");
  }
}
