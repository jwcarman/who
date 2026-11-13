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

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * Handlers for sign-in endpoints that do not redirect: a JSON answer on success, and on failure a
 * {@link SignInFailedException} handed to the application, which formats it.
 *
 * <p>A successful sign-in publishes {@link SignInSucceeded} here rather than from the
 * authentication provider: Spring saves the session before it calls the success handler, so the
 * event means the sign-in is complete.
 */
public class JsonAuthenticationHandlers {

  private final JsonMapper jsonMapper;
  private final WhoExceptionHandoff handoff;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates new handlers that publish no events.
   *
   * @param jsonMapper writes the success body
   * @param handoff hands sign-in failures to the application
   */
  public JsonAuthenticationHandlers(JsonMapper jsonMapper, WhoExceptionHandoff handoff) {
    this(jsonMapper, handoff, WhoEventPublisher.NOOP, Clock.systemUTC());
  }

  /**
   * Creates new handlers.
   *
   * @param jsonMapper writes the success body
   * @param handoff hands sign-in failures to the application
   * @param eventPublisher where {@link SignInSucceeded} is published
   * @param clock stamps the events
   */
  public JsonAuthenticationHandlers(
      JsonMapper jsonMapper,
      WhoExceptionHandoff handoff,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Returns the success handler: {@code 200} with {@code {"identityId": "..."}}. Then, when the
   * authentication is a {@link WhoAuthenticationToken} that knows its {@link
   * WhoAuthenticationToken#signInMethod() sign-in method} and credential, it publishes {@link
   * SignInSucceeded}.
   *
   * @return the success handler
   */
  public AuthenticationSuccessHandler success() {
    return (request, response, authentication) -> {
      WhoPrincipal principal = (WhoPrincipal) authentication.getPrincipal();
      write(
          response,
          HttpServletResponse.SC_OK,
          Map.of("identityId", principal.identity().id().toString()));
      publishSucceeded(authentication, principal);
    };
  }

  private void publishSucceeded(Authentication authentication, WhoPrincipal principal) {
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      return;
    }
    SignInMethod method = token.signInMethod().orElse(null);
    UUID credentialId = token.credentialId().orElse(null);
    if (method != null && credentialId != null) {
      eventPublisher.publish(
          new SignInSucceeded(clock.instant(), method, principal.identity().id(), credentialId));
    }
  }

  /**
   * Returns the failure handler: it hands a {@link SignInFailedException}, wrapping Spring's
   * exception, to the application (falling back to {@code 401}).
   *
   * @return the failure handler
   */
  public AuthenticationFailureHandler failure() {
    return (request, response, exception) ->
        handoff.handOff(
            request, response, new SignInFailedException(exception), HttpStatus.UNAUTHORIZED);
  }

  private void write(HttpServletResponse response, int status, Map<String, String> body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
