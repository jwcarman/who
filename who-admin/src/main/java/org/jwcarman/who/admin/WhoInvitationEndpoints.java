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

import org.jwcarman.who.emaillink.InvitationService;
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
 * Adds {@code POST /invitations} under {@code who.base-path}, which invites a person by email
 * address through {@link InvitationService}. It needs {@link
 * WhoAdminPermissions#IDENTITIES_MANAGE}, which any who sign-in may carry, so it goes on every
 * browser chain and every stateless chain, as {@link WhoAdminEndpoints} does; a browser session
 * must have signed in recently. It grants only roles within the caller's own permissions.
 */
public class WhoInvitationEndpoints implements BrowserEndpoints, StatelessEndpoints {

  private final WhoPaths paths;
  private final InvitationService invitations;
  private final RbacService rbac;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the endpoint.
   *
   * @param paths where it lives
   * @param invitations invites the address
   * @param rbac finds the roles named and reads their permissions
   * @param sessions tells a browser session from a bearer token or an API key, and reads its
   *     sign-in time
   * @param handoff answers errors through the application's exception handling
   * @param jsonMapper reads the request bodies; who writes the answers with its own mapper
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to invite
   */
  public WhoInvitationEndpoints(
      WhoPaths paths,
      InvitationService invitations,
      RbacService rbac,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.invitations = requireNonNull(invitations, "invitations must not be null");
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Adds the endpoint's filter after exception translation, as {@link WhoAdminEndpoints} does, so a
   * signed-out caller gets who's answer; an API key has signed in by then.
   *
   * @param http the chain being built
   */
  @Override
  public void configure(HttpSecurity http) {
    http.addFilterAfter(
        new InvitationsFilter(
            paths,
            invitations,
            rbac,
            new AdminGate(sessions, handoff, clock, recentSignIn),
            jsonMapper),
        ExceptionTranslationFilter.class);
  }
}
