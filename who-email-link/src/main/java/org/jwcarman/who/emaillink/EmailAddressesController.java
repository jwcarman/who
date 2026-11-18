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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
 * removing the last sign-in credential is {@code 409}. An address that does not exist, one that is
 * not an id at all, and one that belongs to someone else are all {@code 404}. Removing the address
 * the session signed in with is allowed and ends that session, which who re-resolves on every
 * request.
 */
@RestController
public class EmailAddressesController {

  private static final String ADDRESS = "address";
  private static final String REPLACES = "replaces";

  private final EmailAddressChanges changes;
  private final EmailCredentialService credentials;
  private final LastCredentialGuard guard;
  private final WhoSessionSecurityContextRepository sessions;
  private final Executor executor;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /**
   * Creates the controller.
   *
   * @param changes requests and confirms the changes
   * @param credentials lists and revokes the addresses
   * @param guard keeps an identity from losing its last sign-in credential
   * @param sessions reads the browser's who session and its sign-in time
   * @param executor runs a request's lookup and sending off the request thread
   * @param jsonMapper reads the request bodies
   * @param clock the current time
   * @param recentSignIn how recently the session must have signed in
   */
  public EmailAddressesController(
      EmailAddressChanges changes,
      EmailCredentialService credentials,
      LastCredentialGuard guard,
      WhoSessionSecurityContextRepository sessions,
      Executor executor,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.changes = requireNonNull(changes, "changes must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.guard = requireNonNull(guard, "guard must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.executor = requireNonNull(executor, "executor must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * The caller's addresses, oldest first. Any signed-in caller may list: it changes nothing.
   *
   * @return the addresses
   */
  @GetMapping(WhoPaths.ROOT + "/me/email-addresses")
  public List<EmailAddressView> list() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      throw new NotSignedInException();
    }
    return credentials.findAllFor(token.getPrincipal().identity().id()).stream()
        .map(
            credential ->
                new EmailAddressView(
                    credential.id().toString(),
                    credential.address(),
                    credential.created().toString()))
        .toList();
  }

  /**
   * Asks to add an address, or to replace one, sending a confirmation to the new address. Answered
   * at once with {@code 202}, the same whatever becomes of it, so a taken address does not show.
   *
   * @param request the request, whose body names the address
   */
  @PostMapping(WhoPaths.ROOT + "/me/email-addresses")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void request(HttpServletRequest request) {
    Identity identity = recentBrowser(request, "add or change");
    Map<String, String> body = JsonBodies.readStrings(jsonMapper, request, ADDRESS, REPLACES);
    UUID replaces = null;
    if (body.containsKey(REPLACES)) {
      replaces =
          parse(body.get(REPLACES))
              .filter(id -> changes.ownsAddress(identity.id(), id))
              .orElseThrow(EmailCredentialNotFoundException::new);
    }
    String address = body.get(ADDRESS);
    UUID replacing = replaces;
    executor.execute(
        RequestLocales.carrying(request, () -> changes.request(identity.id(), address, replacing)));
  }

  /**
   * Confirms an address with the link's token or the code, exactly one of them.
   *
   * @param request the request, whose body carries the token or the code
   * @return the confirmed address's credential id
   */
  @PostMapping(WhoPaths.ROOT + "/me/email-addresses/confirmations")
  public AddedEmailAddress confirm(HttpServletRequest request) {
    Identity identity = recentBrowser(request, "add or change");
    Map<String, String> body = JsonBodies.readStrings(jsonMapper, request, "token", "code");
    String token = body.get("token");
    String code = body.get("code");
    if ((token == null) == (code == null)) {
      throw new InvalidEmailAddressConfirmationException();
    }
    EmailCredential credential =
        token != null ? changes.confirmToken(identity, token) : changes.confirmCode(identity, code);
    return new AddedEmailAddress(credential.id().toString());
  }

  /**
   * Removes one of the caller's addresses, unless it is their last way to sign in.
   *
   * @param id the address's credential id, as the list shows it
   * @param request the request
   */
  @DeleteMapping(WhoPaths.ROOT + "/me/email-addresses/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable("id") String id, HttpServletRequest request) {
    UUID identityId = recentBrowser(request, "remove").id();
    // another identity's address, an unknown one and a malformed id are the same answer
    UUID credentialId =
        parse(id)
            .filter(candidate -> credentials.isOwnedBy(candidate, identityId))
            .orElseThrow(EmailCredentialNotFoundException::new);
    guard.removeIfNotLast(
        identityId,
        () -> {
          credentials.revoke(credentialId);
          return Boolean.TRUE;
        });
  }

  /**
   * The identity of the browser session that signed this request in, which must have signed in
   * recently: signed out is {@code 401}, not a browser {@code 403} (an API key or a bearer token,
   * even one ignored beside the session cookie), not recent {@code 401}.
   */
  private Identity recentBrowser(HttpServletRequest request, String action) {
    WhoCaller caller =
        sessions.caller(request, SecurityContextHolder.getContext().getAuthentication());
    if (caller instanceof WhoCaller.SignedOut) {
      throw new NotSignedInException();
    }
    if (!(caller instanceof WhoCaller.Session session)) {
      throw new BrowserSessionRequiredException(
          "Only a browser session can " + action + " an email address");
    }
    // before looking at the body or the id, so a stale session learns nothing
    if (!session.isRecent(recentSignIn, clock.instant())) {
      throw new ReauthenticationRequiredException();
    }
    return session.token().getPrincipal().identity();
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.ofNullable(id).map(UUID::fromString);
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
