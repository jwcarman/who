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
package org.jwcarman.who.apikey;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;

import org.jwcarman.who.spring.security.BrowserEndpoints;
import org.jwcarman.who.spring.security.StatelessEndpoints;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.ExceptionTranslationFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Adds the endpoints that let a caller list, make, rotate and revoke their own API keys, and read
 * the key a request signed in with, under {@code who.base-path} at {@code /me/api-keys}. They serve
 * browser sessions and bearer tokens alike, so they go on every browser chain and every stateless
 * chain: this is both {@link BrowserEndpoints} and {@link StatelessEndpoints}.
 */
public class ApiKeyEndpoints implements BrowserEndpoints, StatelessEndpoints {

  private final WhoPaths paths;
  private final ApiKeyService keys;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the endpoints.
   *
   * @param paths where the endpoints live
   * @param keys makes, lists, rotates and revokes the keys
   * @param sessions reads the browser's who session and its sign-in time
   * @param handoff answers errors through the application's exception handling
   * @param jsonMapper reads the request bodies and writes the answers
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to make or rotate a key
   */
  public ApiKeyEndpoints(
      WhoPaths paths,
      ApiKeyService keys,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.keys = requireNonNull(keys, "keys must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Adds the endpoints' filter before authorization, as who's other {@code /who/me} endpoints, so a
   * signed-out caller gets who's answer; an API key has signed in by then.
   *
   * @param http the chain being built
   */
  @Override
  public void configure(HttpSecurity http) {
    http.addFilterAfter(
        new ApiKeysFilter(paths, keys, sessions, handoff, jsonMapper, clock, recentSignIn),
        ExceptionTranslationFilter.class);
  }
}
