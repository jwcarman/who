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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * The answers Spring's passkey registration has no hook for. It applies only to {@code POST
 * /webauthn/register/options} and {@code POST /webauthn/register}:
 *
 * <ul>
 *   <li>signed out: {@code 401} with an empty body (Spring alone answers {@code 400});
 *   <li>signed in longer ago than the registration window: {@code 401} with {@code
 *       {"error":"reauthentication_required"}};
 *   <li>a registration that fails verification: {@code 400} with {@code
 *       {"error":"invalid_registration"}} (Spring alone lets the exception escape, so a {@code
 *       500}).
 * </ul>
 *
 * <p>It belongs in the security chain, after {@code ExceptionTranslationFilter}; it is not a
 * servlet filter of its own, so it must not be exposed as a bean.
 */
public class PasskeyRegistrationGuardFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(PasskeyRegistrationGuardFilter.class);

  static final String REGISTRATION_OPTIONS_URL = "/webauthn/register/options";
  static final String REGISTRATION_URL = "/webauthn/register";
  static final String REAUTHENTICATION_REQUIRED = "reauthentication_required";
  static final String INVALID_REGISTRATION = "invalid_registration";

  private static final RequestMatcher OPTIONS =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, REGISTRATION_OPTIONS_URL);
  private static final RequestMatcher REGISTRATION =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, REGISTRATION_URL);

  private final WhoSessionSecurityContextRepository sessionRepository;
  private final Clock clock;
  private final Duration registrationWindow;
  private final JsonMapper jsonMapper;

  /**
   * Creates the guard.
   *
   * @param sessionRepository where the current {@link WhoSession} is read from
   * @param clock the current time
   * @param registrationWindow how long after signing in a passkey may be registered
   * @param jsonMapper writes the error bodies
   */
  public PasskeyRegistrationGuardFilter(
      WhoSessionSecurityContextRepository sessionRepository,
      Clock clock,
      Duration registrationWindow,
      JsonMapper jsonMapper) {
    this.sessionRepository =
        requireNonNull(sessionRepository, "sessionRepository must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.registrationWindow =
        requireNonNull(registrationWindow, "registrationWindow must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !OPTIONS.matches(request) && !REGISTRATION.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<WhoSession> session = sessionRepository.currentSession(request);
    if (session.isEmpty()) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (session.get().signedInAt().plus(registrationWindow).isBefore(clock.instant())) {
      writeError(response, HttpServletResponse.SC_UNAUTHORIZED, REAUTHENTICATION_REQUIRED);
      return;
    }
    if (!REGISTRATION.matches(request)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      chain.doFilter(request, response);
    } catch (AccessDeniedException | AuthenticationException e) {
      // the app's own rules for /webauthn/register; ExceptionTranslationFilter answers those
      throw e;
    } catch (RuntimeException | ServletException e) {
      if (response.isCommitted()) {
        throw e;
      }
      // the exception type only: messages can carry WebAuthn payload details
      log.debug("Passkey registration failed: {}", e.getClass().getName());
      response.resetBuffer();
      writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REGISTRATION);
    }
  }

  private void writeError(HttpServletResponse response, int status, String error)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getOutputStream(), Map.of("error", error));
  }
}
