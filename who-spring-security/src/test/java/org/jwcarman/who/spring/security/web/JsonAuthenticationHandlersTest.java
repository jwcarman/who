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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonAuthenticationHandlersTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final List<Exception> handedOff = new ArrayList<>();
  private final WhoExceptionHandoff.Resolver recording =
      (request, response, exception) -> handedOff.add(exception);
  private final JsonAuthenticationHandlers handlers =
      new JsonAuthenticationHandlers(mapper, new WhoExceptionHandoff(recording));
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final List<WhoEvent> published = new ArrayList<>();
  private final JsonAuthenticationHandlers publishing =
      new JsonAuthenticationHandlers(
          mapper,
          new WhoExceptionHandoff(recording),
          published::add,
          Clock.fixed(NOW, ZoneOffset.UTC));

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
    assertThat(response.getHeader("Content-Type")).contains("charset=UTF-8");
    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.size()).isEqualTo(1);
    assertThat(body.get("identityId").asString()).isEqualTo(identity.id().toString());
  }

  @Test
  void failureHandsOffSignInFailed() throws Exception {
    InvalidOneTimeTokenException cause = new InvalidOneTimeTokenException("invalid_token");

    handlers.failure().onAuthenticationFailure(new MockHttpServletRequest(), response, cause);

    assertThat(handedOff).singleElement().isInstanceOf(SignInFailedException.class);
    assertThat(handedOff.getFirst().getCause()).isNull();
    assertThat(handedOff.getFirst().getSuppressed()).containsExactly(cause);
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(response.getContentType()).isNull();
  }

  @Test
  void successPublishesSignInSucceededWhenTheMethodIsKnown() throws Exception {
    Identity identity = Identity.create();
    UUID credentialId = UUID.randomUUID();

    publishing
        .success()
        .onAuthenticationSuccess(
            new MockHttpServletRequest(),
            response,
            new WhoAuthenticationToken(
                new WhoPrincipal(identity, Set.of()), credentialId, SignInMethod.EMAIL_CODE));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(published)
        .containsExactly(
            new SignInSucceeded(NOW, SignInMethod.EMAIL_CODE, identity.id(), credentialId));
  }

  @Test
  void successWithoutAMethodOrCredentialPublishesNothing() throws Exception {
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of());

    for (WhoAuthenticationToken token :
        List.of(
            new WhoAuthenticationToken(principal),
            new WhoAuthenticationToken(principal, UUID.randomUUID()),
            new WhoAuthenticationToken(principal, null, SignInMethod.EMAIL_LINK))) {
      publishing
          .success()
          .onAuthenticationSuccess(
              new MockHttpServletRequest(), new MockHttpServletResponse(), token);
    }

    assertThat(published).isEmpty();
  }
}
