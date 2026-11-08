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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ResolvableType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.context.WebApplicationContext;

/**
 * Signs a browser in through the application's Spring Session {@link SessionRepository}. Spring
 * Session's request wrapper ignores the {@code MockHttpSession} MockMvc gives the request, so the
 * session must live in the repository and the browser must present its cookie.
 *
 * <p>Only touched when Spring Session is on the classpath.
 */
final class SpringSessionSignIn {

  private static final String COOKIE_NAME_PROPERTY = "server.servlet.session.cookie.name";
  private static final String DEFAULT_COOKIE_NAME = "SESSION";

  private SpringSessionSignIn() {}

  /**
   * Saves a signed-in session in the application's {@link SessionRepository}, if it has one.
   *
   * @return the session cookie the browser must present, or empty if the application has no session
   *     repository
   */
  static Optional<Cookie> signIn(
      WebApplicationContext context, UUID identityId, UUID credentialId, Instant signedInAt) {
    ObjectProvider<SessionRepository<?>> repositories =
        context.getBeanProvider(ResolvableType.forClass(SessionRepository.class));
    SessionRepository<?> repository = repositories.getIfUnique();
    if (repository == null) {
      return Optional.empty();
    }
    String sessionId = save(repository, identityId, credentialId, signedInAt);
    return Optional.of(cookie(context, sessionId));
  }

  private static <S extends Session> String save(
      SessionRepository<S> repository, UUID identityId, UUID credentialId, Instant signedInAt) {
    S session = repository.createSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identityId, credentialId, signedInAt));
    session.setAttribute(
        WhoSessionSecurityContextRepository.PRINCIPAL_INDEX_ATTRIBUTE, identityId.toString());
    repository.save(session);
    return session.getId();
  }

  /**
   * Writes the session cookie with the application's {@link CookieSerializer}, so its name and
   * encoding match what the application reads. Without one, a {@link DefaultCookieSerializer} named
   * by {@code server.servlet.session.cookie.name} (default {@code SESSION}) is used.
   */
  private static Cookie cookie(WebApplicationContext context, String sessionId) {
    CookieSerializer serializer =
        context
            .getBeanProvider(CookieSerializer.class)
            .getIfUnique(() -> defaultSerializer(context));
    MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
    MockHttpServletResponse response = new MockHttpServletResponse();
    serializer.writeCookieValue(new CookieValue(request, response, sessionId));
    Cookie[] written = response.getCookies();
    if (written.length != 1) {
      throw new IllegalStateException(
          "Expected the session CookieSerializer to write one cookie, but it wrote "
              + written.length);
    }
    return written[0];
  }

  private static CookieSerializer defaultSerializer(WebApplicationContext context) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(
        context.getEnvironment().getProperty(COOKIE_NAME_PROPERTY, DEFAULT_COOKIE_NAME));
    return serializer;
  }
}
