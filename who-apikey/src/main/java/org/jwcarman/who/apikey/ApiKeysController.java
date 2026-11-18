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
package org.jwcarman.who.apikey;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.BrowserSessionRequiredException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The caller's API keys under {@code /who/me/api-keys}:
 *
 * <ul>
 *   <li>{@code GET} lists them, by name, {@code lastUsedAt} {@code null} for a key that never
 *       signed in;
 *   <li>{@code POST} with {@code {name, lifetime, scopes}} makes one ({@code lifetime} an ISO-8601
 *       duration) and answers {@code 201} with the raw key;
 *   <li>{@code POST /{id}/rotations} with {@code {overlap}} (an ISO-8601 duration) replaces one, as
 *       {@link ApiKeyService#rotate} does, and answers {@code 201} in the same shape;
 *   <li>{@code DELETE /{id}} revokes one, {@code 204};
 *   <li>{@code GET /current} answers the key that signed this request in, in the list's shape.
 * </ul>
 *
 * <p>The raw key is in the two {@code 201} bodies ({@link CreatedApiKey}) and nowhere else: not in
 * a list, not in {@code /current}, not in a log line or an exception.
 *
 * <p>A browser session (a who session cookie with no API key or bearer token beside it) or a bearer
 * token may manage keys. A browser needs a recent sign-in to make or rotate one; a bearer token
 * does not. A request an API key signed in is {@code 403} {@link ApiKeyCannotManageKeysException}
 * everywhere but {@code /current}, so a key never mints, lists or revokes keys; a session cookie
 * with an API key or bearer token beside it is {@code 403} {@link BrowserSessionRequiredException};
 * {@code /current} without an API key is {@code 404}. A signed-out request is {@code 401}. A key
 * that does not exist and one that belongs to someone else are both {@code 404}, the same answer. A
 * body over {@value ApiKeyBodies#MAX_BODY_BYTES} bytes, or a lifetime, overlap, name or scope who
 * refuses, is {@code 400}.
 */
@RestController
public class ApiKeysController {

  private static final String COLLECTION = WhoPaths.ROOT + "/me/api-keys";

  private final ApiKeyService keys;
  private final WhoSessionSecurityContextRepository sessions;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /** Who is asking: a browser session, or a bearer token when {@code browser} is null. */
  private record Caller(Identity identity, WhoCaller.@Nullable Session browser) {}

  /**
   * Creates the controller.
   *
   * @param keys makes, lists, rotates and revokes the keys
   * @param sessions reads the browser's who session and its sign-in time
   * @param jsonMapper reads the request bodies
   * @param clock the current time
   * @param recentSignIn how recently a browser session must have signed in to make or rotate a key
   */
  public ApiKeysController(
      ApiKeyService keys,
      WhoSessionSecurityContextRepository sessions,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    this.keys = requireNonNull(keys, "keys must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  /**
   * The caller's keys, by name.
   *
   * @param request the request
   * @return the keys, without their secrets
   */
  @GetMapping(COLLECTION)
  public List<ApiKeyView> list(HttpServletRequest request) {
    UUID identityId = manager(request).identity().id();
    return keys.findByIdentity(identityId).stream().map(ApiKeyView::of).toList();
  }

  /**
   * Makes a key.
   *
   * @param request the request, whose body is {@code {name, lifetime, scopes}}
   * @return the key, with its raw secret
   * @throws IOException if the body cannot be read
   */
  @PostMapping(COLLECTION)
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedApiKey create(HttpServletRequest request) throws IOException {
    Caller caller = recentManager(request);
    JsonNode body = ApiKeyBodies.read(jsonMapper, request);
    String name = ApiKeyBodies.requiredString(body, "name");
    Duration lifetime =
        ApiKeyBodies.duration(ApiKeyBodies.requiredString(body, "lifetime"), "lifetime");
    try {
      return CreatedApiKey.of(
          keys.create(caller.identity(), name, lifetime, ApiKeyBodies.scopes(body)));
    } catch (IllegalArgumentException e) {
      throw new InvalidApiKeyRequestException(e.getMessage());
    }
  }

  /**
   * Replaces one of the caller's keys.
   *
   * @param id the key's id
   * @param request the request, whose body is {@code {overlap}}
   * @return the new key, with its raw secret
   * @throws IOException if the body cannot be read
   */
  @PostMapping(COLLECTION + "/{id}/rotations")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedApiKey rotate(@PathVariable("id") String id, HttpServletRequest request)
      throws IOException {
    // before the body or the id is looked at, so a stale session learns nothing about any key
    Caller caller = recentManager(request);
    UUID keyId = owned(id, caller.identity().id());
    Duration overlap =
        ApiKeyBodies.duration(
            ApiKeyBodies.requiredString(ApiKeyBodies.read(jsonMapper, request), "overlap"),
            "overlap");
    try {
      return CreatedApiKey.of(keys.rotate(keyId, overlap));
    } catch (IllegalArgumentException e) {
      // an overlap out of range, or a key that expired or was revoked meanwhile
      throw new InvalidApiKeyRequestException(e.getMessage());
    }
  }

  /**
   * Revokes one of the caller's keys.
   *
   * @param id the key's id
   * @param request the request
   */
  @DeleteMapping(COLLECTION + "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable("id") String id, HttpServletRequest request) {
    keys.revoke(owned(id, manager(request).identity().id()));
  }

  /**
   * The key that signed this request in: the one the authentication itself names, never one the
   * client names.
   *
   * @return the key, without its secret
   */
  @GetMapping(COLLECTION + "/current")
  public ApiKeyView current() {
    WhoAuthenticationToken token = signedIn();
    return token
        .signInMethod()
        .filter(SignInMethod.API_KEY::equals)
        .flatMap(method -> token.credentialId())
        .flatMap(id -> keys.findOwnedBy(token.getPrincipal().identity().id(), id))
        .map(ApiKeyView::of)
        .orElseThrow(ApiKeyNotFoundException::new);
  }

  private static WhoAuthenticationToken signedIn() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      throw new NotSignedInException();
    }
    return token;
  }

  /**
   * The browser session that signed this request in, or the bearer token that did. Anything else,
   * such as an API key, or a session cookie with an API key or bearer token beside it, even one
   * ignored, is refused.
   */
  private Caller manager(HttpServletRequest request) {
    WhoAuthenticationToken token = signedIn();
    Identity identity = token.getPrincipal().identity();
    WhoCaller who = sessions.caller(request, token);
    return switch (who) {
      case WhoCaller.Session session -> new Caller(identity, session);
      case WhoCaller.Stateless stateless when stateless.method() == SignInMethod.JWT ->
          new Caller(identity, null);
      case WhoCaller.Stateless _,
          WhoCaller.SessionWithForeignCredential _,
          WhoCaller.Other _,
          WhoCaller.SignedOut _ ->
          throw refusal(who);
    };
  }

  /** A manager, with a recent sign-in if it is a browser; a bearer token is never stale here. */
  private Caller recentManager(HttpServletRequest request) {
    Caller caller = manager(request);
    if (caller.browser() != null && !caller.browser().isRecent(recentSignIn, clock.instant())) {
      throw new ReauthenticationRequiredException();
    }
    return caller;
  }

  /**
   * Why a caller may not manage keys: an API key never does ({@code api-key-cannot-manage-keys});
   * anything else that is neither a browser session nor a bearer token on its own is {@code
   * browser-session-required}.
   */
  private static AccessDeniedException refusal(WhoCaller who) {
    return who instanceof WhoCaller.Stateless
        ? new ApiKeyCannotManageKeysException()
        : new BrowserSessionRequiredException(
            "Only a browser session or a bearer token on its own can manage API keys");
  }

  /**
   * The id, if it names one of the caller's keys: another identity's key, an unknown one and a
   * malformed id are all {@code 404}. The service checks no owner, so this is the check.
   */
  private UUID owned(String id, UUID identityId) {
    return parse(id)
        .filter(keyId -> keys.findOwnedBy(identityId, keyId).isPresent())
        .orElseThrow(ApiKeyNotFoundException::new);
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
