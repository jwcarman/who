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
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.spring.security.web.WhoJson;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /who/me/email-addresses} asks to add an address, or to change one ({@code replaces}),
 * and {@code POST /who/me/email-addresses/confirmations} confirms it with the link's token or the
 * code. Both need a browser session for the signed-in identity, signed in within the recent sign-in
 * window: signed out is {@code 401}, and an API key or bearer token, even beside the cookie, is
 * {@code 403}.
 *
 * <p>{@code GET /who/me/email-addresses} lists the caller's addresses, oldest first, for any
 * signed-in caller. {@code DELETE /who/me/email-addresses/{id}} removes one, by the credential id
 * the list shows: it is for a browser session only (an API key or bearer token beside the cookie is
 * {@code 403}), needs a recent sign-in, and runs through the {@link LastCredentialGuard}, so
 * removing the last sign-in credential is {@code 409}. An address that does not exist and one that
 * belongs to someone else are both {@code 404}. Removing the address the session signed in with is
 * allowed and ends that session, which who re-resolves on every request.
 */
final class EmailAddressFilter extends OncePerRequestFilter {

  /** The body field naming the address a request replaces. */
  private static final String ADDRESS = "address";

  private static final String REPLACES = "replaces";

  private final RequestMatcher addresses;
  private final RequestMatcher confirm;
  private final RequestMatcher list;
  private final PathPatternRequestMatcher remove;
  private final EmailAddressChanges changes;
  private final EmailCredentialService credentials;
  private final LastCredentialGuard guard;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final Executor executor;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  EmailAddressFilter(
      WhoPaths paths,
      EmailAddressChanges changes,
      EmailCredentialService credentials,
      LastCredentialGuard guard,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      Executor executor,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    String url = requireNonNull(paths, "paths must not be null").of("/me/email-addresses");
    this.addresses = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, url);
    this.confirm =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, url + "/confirmations");
    this.list = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, url);
    this.remove =
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, url + "/{id}");
    this.changes = requireNonNull(changes, "changes must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.executor = requireNonNull(executor, "executor must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !addresses.matches(request)
        && !confirm.matches(request)
        && !list.matches(request)
        && !remove.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (list.matches(request)) {
      list(request, response);
      return;
    }
    if (remove.matches(request)) {
      remove(request, response);
      return;
    }
    Optional<WhoCaller.Session> signedIn = browserSession(request, response, "add or change");
    if (signedIn.isEmpty()) {
      return;
    }
    Identity identity = signedIn.get().token().getPrincipal().identity();
    if (addresses.matches(request)) {
      request(request, response, identity);
    } else {
      confirm(request, response, identity);
    }
  }

  /** Any signed-in caller may list: it changes nothing. */
  private void list(HttpServletRequest request, HttpServletResponse response) throws IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    List<Map<String, Object>> body =
        credentials.findAllFor(token.getPrincipal().identity().id()).stream()
            .map(EmailAddressFilter::view)
            .toList();
    WhoJson.write(response, HttpServletResponse.SC_OK, body);
  }

  private static Map<String, Object> view(EmailCredential credential) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", credential.id().toString());
    view.put(ADDRESS, credential.address());
    view.put("createdAt", credential.created().toString());
    return view;
  }

  private void remove(HttpServletRequest request, HttpServletResponse response) throws IOException {
    Optional<WhoCaller.Session> current = browserSession(request, response, "remove");
    if (current.isEmpty()) {
      return;
    }
    UUID identityId = current.get().token().getPrincipal().identity().id();
    // another identity's address and one that does not exist are the same answer
    Optional<UUID> credentialId =
        parse(remove.matcher(request).getVariables().get("id"))
            .filter(id -> credentials.isOwnedBy(id, identityId));
    if (credentialId.isEmpty()) {
      handoff.handOff(
          request, response, new EmailCredentialNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    try {
      guard.removeIfNotLast(
          identityId,
          () -> {
            credentials.revoke(credentialId.get());
            return Boolean.TRUE;
          });
    } catch (LastCredentialException e) {
      handoff.handOff(request, response, e, HttpStatus.CONFLICT);
      return;
    }
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private void request(HttpServletRequest request, HttpServletResponse response, Identity identity)
      throws IOException {
    Map<String, String> body = JsonBodies.readStrings(jsonMapper, request, ADDRESS, REPLACES);
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
    String address = body.get(ADDRESS);
    UUID replacing = replaces;
    executor.execute(
        RequestLocales.carrying(request, () -> changes.request(identity.id(), address, replacing)));
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
    WhoJson.write(
        response, HttpServletResponse.SC_OK, Map.of("credentialId", credential.id().toString()));
  }

  /**
   * The browser session that signed this request in, which must have signed in recently; anything
   * else is answered: signed out {@code 401}, not a browser {@code 403} (an API key or a bearer
   * token, even one ignored beside the session cookie), not recent {@code 401}.
   */
  private Optional<WhoCaller.Session> browserSession(
      HttpServletRequest request, HttpServletResponse response, String action) throws IOException {
    WhoCaller caller =
        sessions.caller(request, SecurityContextHolder.getContext().getAuthentication());
    if (caller instanceof WhoCaller.SignedOut) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return Optional.empty();
    }
    if (!(caller instanceof WhoCaller.Session session)) {
      handoff.handOff(
          request,
          response,
          new BrowserSessionRequiredException(
              "Only a browser session can " + action + " an email address"),
          HttpStatus.FORBIDDEN);
      return Optional.empty();
    }
    // before looking at the body or the id, so a stale session learns nothing
    if (!session.isRecent(recentSignIn, clock.instant())) {
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
      return Optional.empty();
    }
    return Optional.of(session);
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
