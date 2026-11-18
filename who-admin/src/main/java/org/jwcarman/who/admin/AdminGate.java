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

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The checks every admin endpoint makes before it looks at anything the request names, in this
 * order: signed out is {@code 401}; a request whose permissions lack the one needed is {@code 403};
 * then, for a request that changes something, the {@link WhoCaller caller} decides: a browser
 * session whose sign-in is no longer recent is {@code 401} and must sign in again; a session cookie
 * with an API key or bearer token beside it, which the session signed in and the header did not, is
 * {@code 403}, as is any sign-in that is neither a browser session nor a bearer token or an API
 * key. Only a bearer token or an API key that signed the request in needs no recent sign-in. Each
 * refusal is thrown.
 */
public final class AdminGate {

  private final WhoSessionSecurityContextRepository sessions;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the gate.
   *
   * @param sessions tells a browser session from a key or a token
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to change anything
   */
  public AdminGate(
      WhoSessionSecurityContextRepository sessions, Clock clock, Duration recentSignIn) {
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Admits the caller, or throws why not.
   *
   * @param request the request
   * @param permission the permission the request needs
   * @param changes whether the request changes something, so a browser needs a recent sign-in
   * @return the caller
   */
  public WhoPrincipal admit(HttpServletRequest request, String permission, boolean changes) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      throw new NotSignedInException();
    }
    if (!token.getPrincipal().permissions().contains(permission)) {
      throw new PermissionRequiredException(permission);
    }
    if (changes) {
      switch (sessions.caller(request, token)) {
        case WhoCaller.Stateless _ -> {
          // a bearer token or an API key needs no recent sign-in
        }
        case WhoCaller.Session session -> {
          if (!session.isRecent(recentSignIn, clock.instant())) {
            throw new ReauthenticationRequiredException();
          }
        }
        case WhoCaller.SessionWithForeignCredential _, WhoCaller.Other _, WhoCaller.SignedOut _ ->
            throw new BrowserSessionRequiredException(
                "Only a browser session or a bearer token or API key on its own can change this");
      }
    }
    return token.getPrincipal();
  }
}
