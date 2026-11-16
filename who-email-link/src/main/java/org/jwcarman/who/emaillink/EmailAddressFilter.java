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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /who/email/addresses} asks to add an address, or to change one ({@code replaces}),
 * and {@code POST /who/email/addresses/confirm} confirms it with the link's token or the code. Both
 * need a browser session for the signed-in identity, signed in within the recent sign-in window.
 */
final class EmailAddressFilter extends OncePerRequestFilter {

  static final String ADDRESSES_URL = "/who/email/addresses";
  static final String CONFIRM_URL = ADDRESSES_URL + "/confirm";

  /** The body field naming the address a request replaces. */
  private static final String REPLACES = "replaces";

  private static final RequestMatcher ADDRESSES =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, ADDRESSES_URL);
  private static final RequestMatcher CONFIRM =
      PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, CONFIRM_URL);

  private final EmailAddressChanges changes;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Executor executor;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  EmailAddressFilter(
      EmailAddressChanges changes,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Executor executor,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.changes = requireNonNull(changes, "changes must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.executor = requireNonNull(executor, "executor must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !ADDRESSES.matches(request) && !CONFIRM.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<SignedIn> signedIn = browserSignIn(request);
    if (signedIn.isEmpty()) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    if (signedIn.get().session().signedInAt().plus(recentSignIn).isBefore(clock.instant())) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    if (ADDRESSES.matches(request)) {
      request(request, response, signedIn.get().identity());
    } else {
      confirm(request, response, signedIn.get().identity());
    }
  }

  private void request(HttpServletRequest request, HttpServletResponse response, Identity identity)
      throws IOException {
    Map<String, String> body = JsonBodies.readStrings(jsonMapper, request, "address", REPLACES);
    UUID replaces = null;
    if (body.containsKey(REPLACES)) {
      replaces = parse(body.get(REPLACES)).orElse(null);
      if (replaces == null || !changes.ownsAddress(identity.id(), replaces)) {
        handoff.handOff(
            request, response, new EmailCredentialNotFoundException(), HttpStatus.NOT_FOUND);
        return;
      }
    }
    // answered at once, the same whatever becomes of it, so a taken address does not show
    response.setStatus(HttpServletResponse.SC_ACCEPTED);
    String address = body.get("address");
    UUID replacing = replaces;
    executor.execute(() -> changes.request(identity.id(), address, replacing));
  }

  private void confirm(HttpServletRequest request, HttpServletResponse response, Identity identity)
      throws IOException {
    Map<String, String> body = JsonBodies.readStrings(jsonMapper, request, "token", "code");
    String token = body.get("token");
    String code = body.get("code");
    if ((token == null) == (code == null)) {
      handoff.handOff(
          request,
          response,
          new InvalidEmailAddressConfirmationException(),
          HttpStatus.BAD_REQUEST);
      return;
    }
    EmailCredential credential;
    try {
      credential =
          token != null
              ? changes.confirmToken(identity, token)
              : changes.confirmCode(identity, code);
    } catch (InvalidEmailAddressConfirmationException e) {
      handoff.handOff(request, response, e, HttpStatus.BAD_REQUEST);
      return;
    } catch (EmailAddressTakenException e) {
      handoff.handOff(request, response, e, HttpStatus.CONFLICT);
      return;
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(jsonMapper.writeValueAsString(Map.of("credentialId", credential.id().toString())));
  }

  /**
   * The identity signed in to this browser's who session. A caller signed in some other way, an API
   * key or a bearer token, has no sign-in time to check.
   */
  private Optional<SignedIn> browserSignIn(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      return Optional.empty();
    }
    Identity identity = token.getPrincipal().identity();
    return sessions
        .currentSession(request)
        .filter(session -> session.identityId().equals(identity.id()))
        .map(session -> new SignedIn(identity, session));
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private record SignedIn(Identity identity, WhoSession session) {}
}
