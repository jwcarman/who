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
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;

import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.ExceptionTranslationFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Adds the endpoints that let a signed-in person add an email address, or change one, by confirming
 * a link or code sent to it: {@code POST /who/email/addresses} and {@code POST
 * /who/email/addresses/confirm}, under {@code who.base-path}. Not a way to sign in, but contributed
 * to the browser chain the same way; it adds no public path.
 */
public class EmailAddressEndpoints implements PasswordlessLoginMethod {

  private final WhoPaths paths;
  private final EmailAddressChanges changes;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Executor executor;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the endpoints.
   *
   * @param paths where the endpoints live
   * @param changes requests and confirms the changes
   * @param sessions reads the browser's who session and its sign-in time
   * @param handoff answers errors through the application's exception handling
   * @param executor runs a request's lookup and sending off the request thread
   * @param jsonMapper reads the request bodies and writes the answer
   * @param clock the current time
   * @param recentSignIn how recently the session must have signed in
   */
  public EmailAddressEndpoints(
      WhoPaths paths,
      EmailAddressChanges changes,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Executor executor,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.changes = requireNonNull(changes, "changes must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.executor = requireNonNull(executor, "executor must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  public void configure(HttpSecurity http) {
    // before authorization, as passkeys' guard filter, so a signed-out caller gets who's answer
    http.addFilterAfter(
        new EmailAddressFilter(
            paths, changes, sessions, handoff, executor, jsonMapper, clock, recentSignIn),
        ExceptionTranslationFilter.class);
  }
}
