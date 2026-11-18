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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

class PasskeyRegistrationGuardFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(10);
  private static final String OPTIONS = "/who/me/passkeys/options";
  private static final String REGISTER = "/who/me/passkeys";

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final WhoService whoService = mock(WhoService.class);
  private final WhoSessions whoSessions =
      InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP);
  private final WhoSessionSecurityContextRepository sessions =
      new WhoSessionSecurityContextRepository(
          whoService, whoSessions, clock, WhoEventPublisher.NOOP, _ -> false);
  private final PasskeyRegistrationGuardFilter guard =
      new PasskeyRegistrationGuardFilter(
          WhoPaths.defaults(),
          sessions,
          clock,
          WINDOW,
          new WhoExceptionHandoff(WhoPaths.defaults(), (req, res, e) -> false));

  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain passing = (req, res) -> reachedChain.set(true);

  /** who answered under its base path: its code in the header and in the problem body. */
  private void assertWhoAnswered(String code) throws IOException {
    assertThat(response.getHeader(WhoExceptionHandoff.HEADER)).isEqualTo(code);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
  }

  private static MockHttpServletRequest post(String path) {
    return request("POST", path);
  }

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** A browser session signed in at {@code signedInAt}, loaded as the chain would load it. */
  private MockHttpServletRequest signedInAt(MockHttpServletRequest request, Instant signedInAt) {
    Identity identity = Identity.create(IdentityStatus.ACTIVE);
    UUID credentialId = UUID.randomUUID();
    SessionRecord row =
        whoSessions.start(identity.id(), credentialId, SignInMethod.PASSKEY, null, signedInAt);
    when(whoService.resolveDetailed(any()))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of())));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identity.id(), credentialId, signedInAt, SignInMethod.PASSKEY, row.id()));
    request.setSession(session);
    SecurityContextHolder.setContext(sessions.loadDeferredContext(request).get());
    return request;
  }

  @ParameterizedTest
  @ValueSource(strings = {OPTIONS, REGISTER})
  void guardHandsOffNotSignedInWhenSignedOut(String path) throws ServletException, IOException {
    guard.doFilter(post(path), response, passing);

    assertWhoAnswered("not-signed-in");
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(reachedChain).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {OPTIONS, REGISTER})
  void guardHandsOffReauthenticationRequiredOutsideTheWindow(String path)
      throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(path), NOW.minus(WINDOW).minusSeconds(1));

    guard.doFilter(request, response, passing);

    assertWhoAnswered("reauthentication-required");
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(reachedChain).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    OPTIONS + ",0",
    OPTIONS + ",599",
    REGISTER + ",0",
    REGISTER + ",599",
  })
  void guardLetsRecentSessionsThrough(String path, long secondsAgo)
      throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(path), NOW.minusSeconds(secondsAgo));

    guard.doFilter(request, response, passing);

    assertThat(reachedChain).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {OPTIONS, REGISTER})
  void an_api_key_is_not_a_browser_session(String path) throws ServletException, IOException {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(Identity.create(IdentityStatus.ACTIVE), Set.of()),
                UUID.randomUUID(),
                SignInMethod.API_KEY));

    guard.doFilter(post(path), response, passing);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
    assertThat(reachedChain).isFalse();
  }

  @Test
  void guardHandsOffInvalidPasskeyRegistration() throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(REGISTER), NOW);

    guard.doFilter(
        request,
        response,
        (req, res) -> {
          throw new IllegalArgumentException("verification failed");
        });

    assertWhoAnswered("invalid-passkey-registration");
    // the verifier's own words stay on the server
    assertThat(response.getContentAsString()).doesNotContain("verification failed");
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void guardHandsOffServletExceptionsFromRegistration() throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(REGISTER), NOW);

    guard.doFilter(
        request,
        response,
        (req, res) -> {
          throw new ServletException("wrapped verification failure");
        });

    assertWhoAnswered("invalid-passkey-registration");
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void guardDiscardsWhatAFailedRegistrationBufferedBeforeAnswering()
      throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(REGISTER), NOW);

    guard.doFilter(
        request,
        response,
        (req, res) -> {
          res.getWriter().write("{\"partial\":");
          throw new IllegalArgumentException("verification failed midway");
        });

    assertWhoAnswered("invalid-passkey-registration");
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).doesNotContain("partial");
  }

  @Test
  void guardLeavesCommittedResponsesAlone() throws IOException {
    MockHttpServletRequest request = signedInAt(post(REGISTER), NOW);
    IllegalStateException failure = new IllegalStateException("after the answer");

    assertThatThrownBy(
            () ->
                guard.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      response.setStatus(200);
                      response.getWriter().write("{\"success\":true}");
                      response.flushBuffer();
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("{\"success\":true}");
  }

  @Test
  void guardLeavesAccessDecisionsToSpring() {
    MockHttpServletRequest request = signedInAt(post(REGISTER), NOW);
    AccessDeniedException denied = new AccessDeniedException("denied");

    assertThatThrownBy(
            () ->
                guard.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      throw denied;
                    }))
        .isSameAs(denied);
  }

  @Test
  void guardDoesNotCatchErrorsFromCreationOptions() {
    MockHttpServletRequest request = signedInAt(post(OPTIONS), NOW);
    IllegalArgumentException failure = new IllegalArgumentException("options failed");

    assertThatThrownBy(
            () ->
                guard.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      throw failure;
                    }))
        .isSameAs(failure);
  }

  @ParameterizedTest
  @CsvSource({
    "GET," + REGISTER,
    "GET," + OPTIONS,
    "DELETE," + REGISTER + "/abc",
    "POST," + REGISTER + "/abc",
    "POST,/who/passkey/sign-in/options",
    "POST,/who/passkey/sign-in",
    "POST,/who/email/send",
  })
  void guardIgnoresEverythingElse(String method, String path) throws ServletException, IOException {
    guard.doFilter(request(method, path), response, passing);

    assertThat(reachedChain).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
