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

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoSignInFactors;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The checks every admin endpoint makes before it looks at anything the request names, in this
 * order: signed out is {@code 401}; a request whose permissions lack the one needed is {@code 403};
 * a request that changes something from a browser session (a who session cookie with no API key or
 * bearer token beside it) whose sign-in is no longer recent is {@code 401} and must sign in again.
 * A bearer token or an API key needs no recent sign-in. Every refusal goes through the
 * application's exception handling.
 */
final class AdminGate {

  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Clock clock;
  private final Duration recentSignIn;

  AdminGate(
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Clock clock,
      Duration recentSignIn) {
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Lets the request in, or answers it.
   *
   * @param request the request
   * @param response its response, answered when the request is refused
   * @param permission the permission the request needs
   * @param changes whether the request changes anything, so a browser session must be recent
   * @return the caller's authentication if admitted; empty if the response has been answered
   * @throws IOException if answering fails
   */
  Optional<WhoAuthenticationToken> admit(
      HttpServletRequest request, HttpServletResponse response, String permission, boolean changes)
      throws IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return Optional.empty();
    }
    if (!token.getPrincipal().permissions().contains(permission)) {
      handoff.handOff(
          request, response, new PermissionRequiredException(permission), HttpStatus.FORBIDDEN);
      return Optional.empty();
    }
    if (changes && staleBrowser(request, token)) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return Optional.empty();
    }
    return Optional.of(token);
  }

  /**
   * Answers a request refused after it was admitted, with the status its exception carries.
   *
   * @param request the request
   * @param response its response
   * @param exception why it is refused; {@code 400} if it carries no status
   * @throws IOException if answering fails
   */
  void refuse(HttpServletRequest request, HttpServletResponse response, Exception exception)
      throws IOException {
    handoff.handOff(request, response, exception, HttpStatus.BAD_REQUEST);
  }

  /** A browser session whose sign-in is no longer recent; a bearer token or an API key is not. */
  private boolean staleBrowser(HttpServletRequest request, WhoAuthenticationToken token) {
    return sessions
        .browserSession(request, token)
        .filter(
            session ->
                !WhoSignInFactors.isRecent(session.signedInAt(), recentSignIn, clock.instant()))
        .isPresent();
  }
}
