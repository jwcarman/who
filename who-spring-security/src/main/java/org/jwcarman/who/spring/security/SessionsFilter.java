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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.SessionNotFoundException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /who/me/sessions} lists the caller's live browser sessions; {@code DELETE
 * /who/me/sessions/{id}} ends one; {@code DELETE /who/me/sessions?others=true} ends every one but
 * the caller's. Ending the caller's own session signs it out at once; ending any other needs a
 * sign-in within the recent sign-in window. Only a who browser session may call them. Sessions are
 * named by who's record id, never by the servlet session id, which would let a reader take the
 * session over.
 */
final class SessionsFilter extends OncePerRequestFilter {

  private final RequestMatcher list;
  private final RequestMatcher endAll;
  private final PathPatternRequestMatcher endOne;
  private final WhoSessions sessions;
  private final WhoSessionSecurityContextRepository repository;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;
  private final LogoutHandler signOut;

  SessionsFilter(
      WhoPaths paths,
      WhoSessions sessions,
      WhoSessionSecurityContextRepository repository,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn,
      LogoutHandler signOut) {
    String url = requireNonNull(paths, "paths must not be null").of("/me/sessions");
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
    this.list = matchers.matcher(HttpMethod.GET, url);
    this.endAll = matchers.matcher(HttpMethod.DELETE, url);
    this.endOne = matchers.matcher(HttpMethod.DELETE, url + "/{id}");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.repository = requireNonNull(repository, "repository must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
    this.signOut = requireNonNull(signOut, "signOut must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !list.matches(request) && !endAll.matches(request) && !endOne.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    WhoCaller caller = repository.caller(request, authentication);
    if (caller instanceof WhoCaller.SignedOut) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    if (!(caller instanceof WhoCaller.Session current)) {
      // an API key or a bearer token, even one ignored beside the session cookie, has no browser
      // session to list or keep
      handoff.handOff(
          request,
          response,
          new AccessDeniedException("Only a browser session can manage sessions"),
          HttpStatus.FORBIDDEN);
      return;
    }
    WhoAuthenticationToken token = current.token();
    UUID identityId = token.getPrincipal().identity().id();
    if (list.matches(request)) {
      list(response, identityId, current.session().sessionId());
    } else if (endAll.matches(request)) {
      endOthers(request, response, identityId, current);
    } else {
      endOne(request, response, token, identityId, current);
    }
  }

  private void list(HttpServletResponse response, UUID identityId, UUID currentId)
      throws IOException {
    List<Map<String, Object>> body =
        sessions.list(identityId).stream().map(s -> view(s, currentId)).toList();
    writeJson(response, body);
  }

  private static Map<String, Object> view(SessionRecord session, UUID currentId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", session.id().toString());
    view.put("device", WhoSessions.deviceLabel(session.userAgent()));
    view.put("userAgent", session.userAgent());
    view.put("signInMethod", tagValue(session.signInMethod()));
    view.put("signedInAt", session.signedInAt().toString());
    view.put("lastSeenAt", session.lastSeenAt().toString());
    view.put("current", session.id().equals(currentId));
    return view;
  }

  private void endOthers(
      HttpServletRequest request,
      HttpServletResponse response,
      UUID identityId,
      WhoCaller.Session current)
      throws IOException {
    if (!"true".equals(request.getParameter("others"))) {
      handoff.handOff(
          request,
          response,
          new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "Ending every session needs others=true"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (!recent(request, response, current)) {
      return;
    }
    int ended = sessions.endAllExcept(identityId, current.session().sessionId());
    writeJson(response, Map.of("ended", ended));
  }

  private void endOne(
      HttpServletRequest request,
      HttpServletResponse response,
      WhoAuthenticationToken token,
      UUID identityId,
      WhoCaller.Session current)
      throws IOException {
    Optional<SessionRecord> target =
        parse(endOne.matcher(request).getVariables().get("id"))
            .flatMap(sessions::findLive)
            .filter(s -> s.identityId().equals(identityId));
    if (target.isEmpty()) {
      handoff.handOff(request, response, new SessionNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    if (target.get().id().equals(current.session().sessionId())) {
      signOut.logout(request, response, token);
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }
    if (!recent(request, response, current)) {
      return;
    }
    sessions.end(target.get().id());
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private boolean recent(
      HttpServletRequest request, HttpServletResponse response, WhoCaller.Session current)
      throws IOException {
    if (current.isRecent(recentSignIn, clock.instant())) {
      return true;
    }
    handoff.handOff(
        request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
    return false;
  }

  private void writeJson(HttpServletResponse response, Object body) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(jsonMapper.writeValueAsString(body));
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.ofNullable(id).map(UUID::fromString);
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
