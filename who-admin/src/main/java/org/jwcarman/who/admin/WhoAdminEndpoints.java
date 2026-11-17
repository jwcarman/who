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
package org.jwcarman.who.admin;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.BrowserEndpoints;
import org.jwcarman.who.spring.security.StatelessEndpoints;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.ExceptionTranslationFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Adds who's admin endpoints under {@code who.base-path}: {@code /identities}, to list, read,
 * deactivate and reactivate identities and to assign and remove their roles, and {@code /roles}, to
 * list the roles that exist. Each needs a permission from {@link WhoAdminPermissions}, which any
 * who sign-in may carry: a browser session, a bearer token, or an API key scoped to it. So the
 * endpoints go on every browser chain and every stateless chain: this is both {@link
 * BrowserEndpoints} and {@link StatelessEndpoints}. A browser session that changes an identity or
 * its roles must have signed in recently; a bearer token or an API key need not. A caller may
 * assign only roles within its own permissions, may change only identities that do not outrank it,
 * and may not leave no active identity able to manage identities.
 */
public class WhoAdminEndpoints implements BrowserEndpoints, StatelessEndpoints {

  private final WhoPaths paths;
  private final IdentityRepository identities;
  private final WhoService whoService;
  private final RbacService rbac;
  private final @Nullable IdentityEmails emails;
  private final @Nullable LastCredentialGuard guard;
  private final LastAdminGuard lastAdmin;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the endpoints.
   *
   * @param paths where the endpoints live
   * @param identities pages through and reads identities
   * @param whoService deactivates and reactivates identities
   * @param rbac reads roles and assigns and removes them
   * @param emails the identities' email addresses; null without email sign-in, when the endpoints
   *     show no addresses and refuse a search by address
   * @param guard counts each identity's sign-in credentials; null when there is none, when the
   *     counts are empty
   * @param lastAdmin keeps deactivations and role removals from leaving no one who may manage
   *     identities
   * @param sessions tells a browser session from a bearer token or an API key, and reads its
   *     sign-in time
   * @param handoff answers errors through the application's exception handling
   * @param jsonMapper reads the request bodies; who writes the answers with its own mapper
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to change an identity
   */
  public WhoAdminEndpoints(
      WhoPaths paths,
      IdentityRepository identities,
      WhoService whoService,
      RbacService rbac,
      @Nullable IdentityEmails emails,
      @Nullable LastCredentialGuard guard,
      LastAdminGuard lastAdmin,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.identities = requireNonNull(identities, "identities must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.emails = emails;
    this.guard = guard;
    this.lastAdmin = requireNonNull(lastAdmin, "lastAdmin must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Adds the endpoints' filter after exception translation, as who's {@code /who/me} endpoints, so
   * a signed-out caller gets who's answer; an API key has signed in by then.
   *
   * @param http the chain being built
   */
  @Override
  public void configure(HttpSecurity http) {
    http.addFilterAfter(
        new IdentitiesFilter(
            paths,
            identities,
            whoService,
            rbac,
            emails,
            guard,
            lastAdmin,
            new AdminGate(sessions, handoff, clock, recentSignIn),
            jsonMapper),
        ExceptionTranslationFilter.class);
  }
}
