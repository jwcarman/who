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
package org.jwcarman.who.spring.security;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;

import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * Adds the endpoints that let a signed-in person see their browser sessions and end them: {@code
 * GET /who/sessions}, {@code DELETE /who/sessions/{id}} and {@code DELETE
 * /who/sessions?others=true}. {@code WhoWebSecurity.passwordless()} adds them to every browser
 * chain when this bean exists. Not a way to sign in, so it does not make a chain a browser chain,
 * and it adds no public path.
 */
public class SessionEndpoints {

  private final WhoSessions sessions;
  private final WhoSessionSecurityContextRepository repository;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the endpoints.
   *
   * @param sessions who's record of sessions
   * @param repository reads the browser's who session and signs it out
   * @param handoff answers errors through the application's exception handling
   * @param jsonMapper writes the answers
   * @param clock the current time
   * @param recentSignIn how recently the session must have signed in to end another session
   */
  public SessionEndpoints(
      WhoSessions sessions,
      WhoSessionSecurityContextRepository repository,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Adds the endpoints' filter to a browser chain.
   *
   * @param http the chain
   * @param logout the chain's logout, whose handlers sign this browser out
   * @param paths where the endpoints live
   */
  void configure(HttpSecurity http, LogoutConfigurer<HttpSecurity> logout, WhoPaths paths) {
    // before authorization, as the email address endpoints, so a signed-out caller gets who's
    // answer
    http.addFilterAfter(
        new SessionsFilter(
            paths,
            sessions,
            repository,
            handoff,
            jsonMapper,
            clock,
            recentSignIn,
            signOutHandler(logout)),
        ExceptionTranslationFilter.class);
  }

  /*
   * Signing out this browser through the endpoints runs the same handlers as the chain's logout:
   * who's, the app's own, CSRF's and cookie clearing, the context's, and LogoutSuccessEvent. The
   * chain adds the last two when it is built, so the handlers are read when a request needs them.
   * Should the app turn logout off, those two never come, so the context is always ended after
   * them too; ending it twice is harmless.
   */
  static LogoutHandler signOutHandler(LogoutConfigurer<HttpSecurity> logout) {
    LogoutHandler endContext = new SecurityContextLogoutHandler();
    return (request, response, authentication) -> {
      new CompositeLogoutHandler(logout.getLogoutHandlers())
          .logout(request, response, authentication);
      endContext.logout(request, response, authentication);
    };
  }
}
