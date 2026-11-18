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
import static org.jwcarman.who.core.observation.WhoObservations.tagValue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.SessionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /who/me/sessions} lists the caller's live browser sessions; {@code DELETE
 * /who/me/sessions/{id}} ends one; {@code DELETE /who/me/sessions?others=true} ends every one but
 * the caller's. Ending the caller's own session signs it out at once, through the chain's logout
 * (see {@link ChainLogout}); ending any other needs a sign-in within the recent sign-in window.
 * Only a who browser session may call them. Sessions are named by who's record id, never by the
 * servlet session id, which would let a reader take the session over.
 */
@RestController
public class SessionsController {

  private final WhoSessions sessions;
  private final WhoSessionSecurityContextRepository repository;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the controller.
   *
   * @param sessions who's record of sessions
   * @param repository reads the browser's who session
   * @param clock the current time
   * @param recentSignIn how recently the session must have signed in to end another session
   */
  public SessionsController(
      WhoSessions sessions,
      WhoSessionSecurityContextRepository repository,
      Clock clock,
      Duration recentSignIn) {
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * Lists the caller's live sessions, newest first, the current one marked.
   *
   * @param request the request
   * @return the sessions
   */
  @GetMapping(WhoPaths.MAPPING + "/me/sessions")
  public List<SessionView> list(HttpServletRequest request) {
    WhoCaller.Session current = browser(request);
    UUID currentId = current.session().sessionId();
    return sessions.list(identityOf(current)).stream().map(s -> view(s, currentId)).toList();
  }

  /**
   * Ends every one of the caller's sessions but this one. Needs a recent sign-in.
   *
   * @param others must be {@code true}
   * @param request the request
   * @return how many ended
   */
  @DeleteMapping(WhoPaths.MAPPING + "/me/sessions")
  public EndedSessions endOthers(
      @RequestParam(name = "others", required = false) @Nullable String others,
      HttpServletRequest request) {
    WhoCaller.Session current = browser(request);
    if (!"true".equals(others)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Ending every session needs others=true");
    }
    requireRecent(current);
    return new EndedSessions(
        sessions.endAllExcept(identityOf(current), current.session().sessionId()));
  }

  /**
   * Ends one of the caller's sessions: the caller's own signs this browser out; any other needs a
   * recent sign-in.
   *
   * @param id who's id for the session
   * @param request the request
   * @param response the response, which signing out may set cookies on
   */
  @DeleteMapping(WhoPaths.MAPPING + "/me/sessions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void endOne(
      @PathVariable("id") String id, HttpServletRequest request, HttpServletResponse response) {
    WhoCaller.Session current = browser(request);
    SessionRecord target =
        parse(id)
            .flatMap(sessions::findLive)
            .filter(s -> s.identityId().equals(identityOf(current)))
            .orElseThrow(SessionNotFoundException::new);
    if (target.id().equals(current.session().sessionId())) {
      chainLogout(request).logout(request, response, current.token());
      return;
    }
    requireRecent(current);
    sessions.end(target.id());
  }

  private WhoCaller.Session browser(HttpServletRequest request) {
    WhoCaller caller =
        repository.caller(request, SecurityContextHolder.getContext().getAuthentication());
    if (caller instanceof WhoCaller.SignedOut) {
      throw new NotSignedInException();
    }
    if (!(caller instanceof WhoCaller.Session current)) {
      // an API key or a bearer token, even one ignored beside the session cookie, has no browser
      // session to list or keep
      throw new BrowserSessionRequiredException("Only a browser session can manage sessions");
    }
    return current;
  }

  private void requireRecent(WhoCaller.Session current) {
    if (!current.isRecent(recentSignIn, clock.instant())) {
      throw new ReauthenticationRequiredException();
    }
  }

  private static LogoutHandler chainLogout(HttpServletRequest request) {
    if (request.getAttribute(ChainLogout.ATTRIBUTE) instanceof LogoutHandler handler) {
      return handler;
    }
    // only a who browser session gets here, and every who browser chain adds ChainLogout
    throw new IllegalStateException("No chain logout for a who browser session");
  }

  private static UUID identityOf(WhoCaller.Session current) {
    return current.token().getPrincipal().identity().id();
  }

  private static SessionView view(SessionRecord session, UUID currentId) {
    return new SessionView(
        session.id().toString(),
        WhoSessions.deviceLabel(session.userAgent()),
        session.userAgent(),
        tagValue(session.signInMethod()),
        session.signedInAt().toString(),
        session.lastSeenAt().toString(),
        session.id().equals(currentId));
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
