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
package org.jwcarman.who.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.context.support.GenericWebApplicationContext;

class SpringSessionSignInTest {

  private static final Instant SIGNED_IN_AT = Instant.parse("2026-11-07T10:00:00Z");

  private final UUID identityId = UUID.randomUUID();
  private final UUID credentialId = UUID.randomUUID();

  @Test
  void withoutASessionRepositoryNothingIsSaved() {
    GenericWebApplicationContext context = context(new MockEnvironment());
    context.refresh();

    assertThat(SpringSessionSignIn.signIn(context, identityId, credentialId, SIGNED_IN_AT))
        .isEmpty();
  }

  @Test
  void withoutACookieSerializerTheConfiguredCookieNameAndBase64IdAreUsed() {
    Map<String, Session> store = new HashMap<>();
    GenericWebApplicationContext context =
        context(new MockEnvironment().withProperty("server.servlet.session.cookie.name", "SID"));
    context.registerBean(MapSessionRepository.class, () -> new MapSessionRepository(store));
    context.refresh();

    Optional<Cookie> cookie =
        SpringSessionSignIn.signIn(context, identityId, credentialId, SIGNED_IN_AT);

    assertThat(store).hasSize(1);
    MapSession saved = (MapSession) store.values().iterator().next();
    assertThat(cookie)
        .get()
        .returns("SID", Cookie::getName)
        .returns(
            Base64.getEncoder().encodeToString(saved.getId().getBytes(StandardCharsets.UTF_8)),
            Cookie::getValue);
    assertThat(
            saved.<WhoSession>getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .isEqualTo(new WhoSession(identityId, credentialId, SIGNED_IN_AT));
    assertThat(
            saved.<String>getAttribute(
                WhoSessionSecurityContextRepository.PRINCIPAL_INDEX_ATTRIBUTE))
        .isEqualTo(identityId.toString());
  }

  @Test
  void aCookieSerializerThatWritesNoCookieIsRejected() {
    GenericWebApplicationContext context = context(new MockEnvironment());
    context.registerBean(
        MapSessionRepository.class, () -> new MapSessionRepository(new HashMap<>()));
    context.registerBean(CookieSerializer.class, SilentCookieSerializer::new);
    context.refresh();

    assertThatThrownBy(
            () -> SpringSessionSignIn.signIn(context, identityId, credentialId, SIGNED_IN_AT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Expected the session CookieSerializer to write one cookie, but it wrote 0");
  }

  private static final class SilentCookieSerializer implements CookieSerializer {

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
      // writes nothing
    }

    @Override
    public List<String> readCookieValues(HttpServletRequest request) {
      return List.of();
    }
  }

  private static GenericWebApplicationContext context(MockEnvironment environment) {
    GenericWebApplicationContext context = new GenericWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.setEnvironment(environment);
    return context;
  }
}
