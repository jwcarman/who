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
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.InvalidPasskeyRegistrationException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;

class PasskeyRegistrationGuardFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(10);
  private static final String OPTIONS = "/webauthn/register/options";
  private static final String REGISTER = "/webauthn/register";

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final WhoSessionSecurityContextRepository sessions =
      new WhoSessionSecurityContextRepository(mock(WhoService.class), clock);
  private final List<Exception> handedOff = new ArrayList<>();
  private final PasskeyRegistrationGuardFilter guard =
      new PasskeyRegistrationGuardFilter(
          sessions, clock, WINDOW, new WhoExceptionHandoff((req, res, e) -> !handedOff.add(e)));

  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain passing = (req, res) -> reachedChain.set(true);

  private static MockHttpServletRequest post(String path) {
    return request("POST", path);
  }

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  private static MockHttpServletRequest signedInAt(MockHttpServletRequest request, Instant when) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(UUID.randomUUID(), UUID.randomUUID(), when));
    request.setSession(session);
    return request;
  }

  @ParameterizedTest
  @ValueSource(strings = {OPTIONS, REGISTER})
  void guardHandsOffNotSignedInWhenSignedOut(String path) throws ServletException, IOException {
    guard.doFilter(post(path), response, passing);

    assertThat(handedOff).singleElement().isInstanceOf(NotSignedInException.class);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(reachedChain).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {OPTIONS, REGISTER})
  void guardHandsOffReauthenticationRequiredOutsideTheWindow(String path)
      throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(path), NOW.minus(WINDOW).minusSeconds(1));

    guard.doFilter(request, response, passing);

    assertThat(handedOff).singleElement().isInstanceOf(ReauthenticationRequiredException.class);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(reachedChain).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    OPTIONS + ",0",
    OPTIONS + ",600",
    REGISTER + ",0",
    REGISTER + ",600",
  })
  void guardLetsRecentSessionsThrough(String path, long secondsAgo)
      throws ServletException, IOException {
    MockHttpServletRequest request = signedInAt(post(path), NOW.minusSeconds(secondsAgo));

    guard.doFilter(request, response, passing);

    assertThat(reachedChain).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEmpty();
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

    assertThat(handedOff)
        .singleElement()
        .isInstanceOf(InvalidPasskeyRegistrationException.class)
        .extracting(Throwable::getCause)
        .isNull();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).isEmpty();
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

    assertThat(handedOff).singleElement().isInstanceOf(InvalidPasskeyRegistrationException.class);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).isEmpty();
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
    "POST,/webauthn/authenticate/options",
    "POST,/login/webauthn",
    "POST,/ott/generate",
  })
  void guardIgnoresEverythingElse(String method, String path) throws ServletException, IOException {
    guard.doFilter(request(method, path), response, passing);

    assertThat(reachedChain).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
