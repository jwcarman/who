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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A {@link MockMvc} client that behaves like a single-page app in a browser against a chain built
 * with {@code WhoWebSecurity.passwordless()}.
 *
 * <p>It keeps one HTTP session and every cookie the application sets, including deletions such as
 * the one on logout. On {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} it echoes the
 * {@code XSRF-TOKEN} cookie into the {@code X-XSRF-TOKEN} header, as an SPA does with {@code
 * csrf.spa()}; if it holds no token yet, it first fetches one with a {@code GET}. (Spring Security
 * Test's {@code csrf().asHeader()} sends a masked token, which {@code csrf.spa()} rejects.)
 *
 * <p>An instance is not thread-safe.
 */
public class WhoMockMvcBrowser {

  private static final String XSRF_COOKIE = "XSRF-TOKEN";
  private static final String XSRF_HEADER = "X-XSRF-TOKEN";
  private static final String TOKEN_URL = "/who/me";
  private static final Set<HttpMethod> UNSAFE =
      Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

  private final MockMvc mvc;
  private final Map<String, Cookie> cookies = new LinkedHashMap<>();
  private MockHttpSession session = new MockHttpSession();

  /**
   * Creates a browser with no session and no cookies.
   *
   * @param mvc the {@code MockMvc} to send requests through, built with Spring Security applied
   */
  public WhoMockMvcBrowser(MockMvc mvc) {
    this.mvc = mvc;
  }

  /**
   * Starts a new session signed in as the given identity and credential, replacing the current
   * session. Cookies are kept. See {@link WhoTestSessions#signedIn(UUID, UUID)}.
   *
   * @param identityId the signed-in identity
   * @param credentialId the credential it signed in with
   * @return this browser
   */
  public WhoMockMvcBrowser signedInAs(UUID identityId, UUID credentialId) {
    session = WhoTestSessions.signedIn(identityId, credentialId);
    return this;
  }

  /**
   * Sends a request with this browser's session and cookies, then remembers the cookies the
   * response sets. On an unsafe method, the CSRF token is echoed in {@code X-XSRF-TOKEN}.
   *
   * @param request the request to send
   * @return the result, to assert on
   * @throws Exception if the request fails
   */
  public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    if (isUnsafe(request) && !cookies.containsKey(XSRF_COOKIE)) {
      send(MockMvcRequestBuilders.get(TOKEN_URL));
    }
    Cookie token = cookies.get(XSRF_COOKIE);
    if (isUnsafe(request) && token != null) {
      request.header(XSRF_HEADER, token.getValue());
    }
    return send(request);
  }

  /**
   * Returns a cookie the browser currently holds.
   *
   * @param name the cookie name
   * @return the cookie, or empty if none is held (never set, or deleted by the application)
   */
  public Optional<Cookie> cookie(String name) {
    return Optional.ofNullable(cookies.get(name));
  }

  /**
   * Returns the browser's current HTTP session; a new one replaces it after the application
   * invalidates it, for example on logout.
   *
   * @return the session
   */
  public MockHttpSession session() {
    return session;
  }

  private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
    request
        .session(session)
        .with(
            r -> {
              // as a servlet container does when the browser presents its session cookie
              r.setRequestedSessionId(session.getId());
              r.setRequestedSessionIdValid(true);
              return r;
            });
    cookies.values().forEach(request::cookie);
    ResultActions actions = mvc.perform(request);
    remember(actions.andReturn().getResponse().getCookies());
    if (session.isInvalid()) {
      session = new MockHttpSession();
    }
    return actions;
  }

  private void remember(Cookie[] set) {
    for (Cookie cookie : set) {
      if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) {
        cookies.remove(cookie.getName());
      } else {
        cookies.put(cookie.getName(), cookie);
      }
    }
  }

  private static boolean isUnsafe(MockHttpServletRequestBuilder request) {
    String method = request.buildRequest(new MockServletContext()).getMethod();
    return UNSAFE.contains(HttpMethod.valueOf(method));
  }
}
