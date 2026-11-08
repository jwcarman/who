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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * A {@link MockMvc} client that behaves like a single-page app in a browser against a chain built
 * with {@code WhoWebSecurity.passwordless()}.
 *
 * <p>The browser owns three things on every request it sends, whatever the caller's builder says:
 *
 * <ul>
 *   <li>the HTTP session: one session is kept across requests, replaced by the one the application
 *       creates, or by a fresh one after the application invalidates it (for example on logout).
 *       Under Spring Session the session is the application's own, found by the session cookie the
 *       browser holds (see {@link #signedInAs(UUID, UUID)});
 *   <li>the cookies it holds, which it keeps from every response, deleting those the application
 *       expires. On a name clash the browser's cookie wins over one set on the builder; other
 *       builder cookies are kept;
 *   <li>the {@code X-XSRF-TOKEN} header on {@code POST}, {@code PUT}, {@code PATCH} and {@code
 *       DELETE}: it is set to the held {@code XSRF-TOKEN} cookie, as an SPA does with {@code
 *       csrf.spa()}. (Spring Security Test's {@code csrf().asHeader()} sends a masked token, which
 *       {@code csrf.spa()} rejects.)
 * </ul>
 *
 * <p>The token comes from {@code GET /who/me}, so this assumes a {@code
 * WhoWebSecurity.passwordless()} chain: whenever the browser holds no {@code XSRF-TOKEN} cookie (on
 * its first request, and after logout deleted it), it first sends that {@code GET}, whatever the
 * method of the request. If no token arrives, an unsafe request is sent without the header.
 *
 * <p>An instance is not thread-safe.
 */
public class WhoMockMvcBrowser {

  private static final String XSRF_COOKIE = "XSRF-TOKEN";
  private static final String XSRF_HEADER = "X-XSRF-TOKEN";
  private static final String TOKEN_URL = "/who/me";
  private static final Set<HttpMethod> UNSAFE =
      Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

  private static final boolean SPRING_SESSION_PRESENT =
      ClassUtils.isPresent(
          "org.springframework.session.SessionRepository",
          WhoMockMvcBrowser.class.getClassLoader());

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
   * Starts a new session signed in now ({@link Instant#now()}) as the given identity and
   * credential, replacing the current session. Other cookies are kept. When the application's
   * {@code Clock} is fixed, use {@link #signedInAs(UUID, UUID, Instant)} with the clock's instant
   * instead.
   *
   * <p>If Spring Session is on the classpath and the application has a {@code SessionRepository}
   * bean, the session is saved in that repository and the browser holds its session cookie, written
   * by the application's {@code CookieSerializer} (or, without one, named by {@code
   * server.servlet.session.cookie.name}, default {@code SESSION}). Spring Session's filter ignores
   * a {@code MockHttpSession}, so this is the only way to sign in under it. Otherwise the browser
   * holds a {@code MockHttpSession} like {@link WhoTestSessions#signedIn(UUID, UUID)}.
   *
   * @param identityId the signed-in identity
   * @param credentialId the credential it signed in with
   * @return this browser
   */
  public WhoMockMvcBrowser signedInAs(UUID identityId, UUID credentialId) {
    return signedInAs(identityId, credentialId, Instant.now());
  }

  /**
   * Starts a new session signed in as the given identity and credential at the given instant,
   * replacing the current session, as {@link #signedInAs(UUID, UUID)} does (with Spring Session
   * too). Use this overload when the application's {@code Clock} is fixed, so the sign-in time
   * follows that clock. Other cookies are kept.
   *
   * @param identityId the signed-in identity
   * @param credentialId the credential it signed in with
   * @param signedInAt when the identity signed in
   * @return this browser
   */
  public WhoMockMvcBrowser signedInAs(UUID identityId, UUID credentialId, Instant signedInAt) {
    Optional<Cookie> sessionCookie = Optional.empty();
    WebApplicationContext context = mvc.getDispatcherServlet().getWebApplicationContext();
    if (SPRING_SESSION_PRESENT && context != null) {
      sessionCookie = SpringSessionSignIn.signIn(context, identityId, credentialId, signedInAt);
    }
    if (sessionCookie.isPresent()) {
      session = new MockHttpSession();
      cookies.put(sessionCookie.get().getName(), sessionCookie.get());
    } else {
      session = WhoTestSessions.signedIn(identityId, credentialId, signedInAt);
    }
    return this;
  }

  /**
   * Sends a request with this browser's session, cookies and, on an unsafe method, CSRF header (see
   * the class description for what the browser owns), then remembers the cookies and session the
   * response leaves. If the browser holds no {@code XSRF-TOKEN} cookie, it first sends {@code GET
   * /who/me} to obtain one, so the first request and the first one after logout cost an extra
   * request. A post-processor on the builder runs once, on the request itself.
   *
   * @param request the request to send
   * @return the result, to assert on
   * @throws Exception if the request fails
   */
  public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    if (!cookies.containsKey(XSRF_COOKIE)) {
      send(MockMvcRequestBuilders.get(TOKEN_URL));
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
   * Returns the browser's current HTTP session: the one the application last created for it, or a
   * fresh one after the application invalidated the previous session, for example on logout. Under
   * Spring Session the application's session lives in its {@code SessionRepository}, found by the
   * session cookie, and this one is unused.
   *
   * @return the session
   */
  public MockHttpSession session() {
    return session;
  }

  private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
    request.with(this::apply);
    ResultActions actions = mvc.perform(request);
    MvcResult result = actions.andReturn();
    remember(result.getResponse().getCookies());
    adoptSession(result.getRequest().getSession(false));
    return actions;
  }

  private MockHttpServletRequest apply(MockHttpServletRequest request) {
    request.setSession(session);
    // as a servlet container does when the browser presents its session cookie
    request.setRequestedSessionId(session.getId());
    request.setRequestedSessionIdValid(true);
    Map<String, Cookie> merged = new LinkedHashMap<>();
    for (Cookie own : Optional.ofNullable(request.getCookies()).orElse(new Cookie[0])) {
      merged.put(own.getName(), own);
    }
    merged.putAll(cookies);
    request.setCookies(merged.values().toArray(new Cookie[0]));
    Cookie token = cookies.get(XSRF_COOKIE);
    String method = request.getMethod();
    if (token != null && method != null && UNSAFE.contains(HttpMethod.valueOf(method))) {
      request.removeHeader(XSRF_HEADER);
      request.addHeader(XSRF_HEADER, token.getValue());
    }
    return request;
  }

  private void adoptSession(HttpSession current) {
    if (current instanceof MockHttpSession created && created != session && !created.isInvalid()) {
      session = created;
    } else if (session.isInvalid()) {
      session = new MockHttpSession();
    }
  }

  private void remember(Cookie[] set) {
    for (Cookie cookie : set) {
      if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty()) {
        cookies.remove(cookie.getName());
      } else {
        cookies.put(cookie.getName(), cookie);
      }
    }
  }
}
