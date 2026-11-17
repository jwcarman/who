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
package org.jwcarman.who.oidc;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;

import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.StatelessEndpoints;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.ExceptionTranslationFilter;

/**
 * The caller's linked provider accounts, {@code GET /who/me/accounts} and {@code DELETE
 * /who/me/accounts/{id}}, on every stateless chain, where an API key or bearer token may list them
 * and unlinking, which only a browser may do, answers {@code 403}. A browser chain gets them from
 * {@link OidcLoginMethod} with the rest of provider sign-in.
 */
public class LinkedAccountsEndpoints implements StatelessEndpoints {

  private final WhoPaths paths;
  private final SignInRegistrations registrations;
  private final FederatedCredentialService accounts;
  private final LastCredentialGuard lastCredentialGuard;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the endpoints.
   *
   * @param paths where the endpoints live
   * @param registrations names each account's provider
   * @param accounts lists and unlinks the caller's accounts
   * @param lastCredentialGuard keeps an identity from losing its last sign-in credential
   * @param sessions tells a browser session from a key or token
   * @param handoff answers errors through the application's exception handling
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to unlink an account
   */
  public LinkedAccountsEndpoints(
      WhoPaths paths,
      SignInRegistrations registrations,
      FederatedCredentialService accounts,
      LastCredentialGuard lastCredentialGuard,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Clock clock,
      Duration recentSignIn) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.registrations = requireNonNull(registrations, "registrations must not be null");
    this.accounts = requireNonNull(accounts, "accounts must not be null");
    this.lastCredentialGuard =
        requireNonNull(lastCredentialGuard, "lastCredentialGuard must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Adds the endpoints' filter after exception translation, as on a browser chain.
   *
   * @param http the stateless chain being built
   */
  @Override
  public void configure(HttpSecurity http) {
    http.addFilterAfter(
        new LinkedAccountsFilter(
            paths,
            registrations,
            accounts,
            lastCredentialGuard,
            sessions,
            handoff,
            clock,
            recentSignIn),
        ExceptionTranslationFilter.class);
  }
}
