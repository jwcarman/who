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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The caller's API keys under {@code /who/me/api-keys}:
 *
 * <ul>
 *   <li>{@code GET} lists them, by name, as {@code [{id, name, scopes, created, expiresAt,
 *       lastUsed}]};
 *   <li>{@code POST} with {@code {name, lifetime, scopes}} makes one ({@code lifetime} an ISO-8601
 *       duration) and answers {@code 201} with {@code {id, name, scopes, expiresAt, key}};
 *   <li>{@code POST /{id}/rotations} with {@code {overlap}} (an ISO-8601 duration) replaces one, as
 *       {@link ApiKeyService#rotate} does, and answers {@code 201} in the same shape;
 *   <li>{@code DELETE /{id}} revokes one, {@code 204};
 *   <li>{@code GET /current} answers the key that signed this request in, in the list's shape.
 * </ul>
 *
 * <p>The raw key is in the two {@code 201} bodies and nowhere else: not in a list, not in {@code
 * /current}, not in a log line or an exception.
 *
 * <p>A browser session (a who session cookie with no API key or bearer token beside it) or a bearer
 * token may manage keys. A browser needs a recent sign-in to make or rotate one; a bearer token
 * does not. A request an API key signed in is {@code 403} everywhere but {@code /current}, so a key
 * never mints, lists or revokes keys; {@code /current} without an API key is {@code 404}. A
 * signed-out request is {@code 401}. A key that does not exist and one that belongs to someone else
 * are both {@code 404}, the same answer. A body, lifetime, overlap, name or scope who refuses is
 * {@code 400}.
 */
final class ApiKeysFilter extends OncePerRequestFilter {

  /** The largest body read, in bytes. */
  static final int MAX_BODY_BYTES = 8192;

  private static final String COLLECTION = "/me/api-keys";

  private final RequestMatcher list;
  private final RequestMatcher create;
  private final RequestMatcher current;
  private final PathPatternRequestMatcher revoke;
  private final PathPatternRequestMatcher rotate;
  private final ApiKeyService keys;
  private final WhoSessionSecurityContextRepository sessions;
  private final WhoExceptionHandoff handoff;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration recentSignIn;

  /** Who is asking: a browser session, or a bearer token when {@code browser} is null. */
  private record Caller(Identity identity, WhoCaller.Session browser) {}

  ApiKeysFilter(
      WhoPaths paths,
      ApiKeyService keys,
      WhoSessionSecurityContextRepository sessions,
      WhoExceptionHandoff handoff,
      JsonMapper jsonMapper,
      Clock clock,
      Duration recentSignIn) {
    String url = requireNonNull(paths, "paths must not be null").of(COLLECTION);
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
    this.list = matchers.matcher(HttpMethod.GET, url);
    this.create = matchers.matcher(HttpMethod.POST, url);
    this.current = matchers.matcher(HttpMethod.GET, url + "/current");
    this.revoke = matchers.matcher(HttpMethod.DELETE, url + "/{id}");
    this.rotate = matchers.matcher(HttpMethod.POST, url + "/{id}/rotations");
    this.keys = requireNonNull(keys, "keys must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.handoff = requireNonNull(handoff, "handoff must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.recentSignIn = requireNonNull(recentSignIn, "recentSignIn must not be null");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !list.matches(request)
        && !create.matches(request)
        && !current.matches(request)
        && !revoke.matches(request)
        && !rotate.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof WhoAuthenticationToken token)) {
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
      return;
    }
    if (current.matches(request)) {
      current(request, response, token);
      return;
    }
    Optional<Caller> caller = caller(request, token);
    if (caller.isEmpty()) {
      handoff.handOff(
          request,
          response,
          new AccessDeniedException("Only a browser session or a bearer token can manage API keys"),
          HttpStatus.FORBIDDEN);
      return;
    }
    UUID identityId = caller.get().identity().id();
    if (list.matches(request)) {
      writeJson(
          response,
          HttpServletResponse.SC_OK,
          keys.findByIdentity(identityId).stream().map(Listed::of).toList());
    } else if (revoke.matches(request)) {
      revoke(request, response, identityId);
    } else if (stale(caller.get())) {
      // before the body or the id is looked at, so a stale session learns nothing about any key
      handoff.handOff(
          request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
    } else if (create.matches(request)) {
      create(request, response, caller.get().identity());
    } else {
      rotate(request, response, identityId);
    }
  }

  /**
   * The browser session that signed this request in, or the bearer token that did; empty for
   * anything else, such as an API key, or a session cookie with an API key or bearer token beside
   * it, even one ignored.
   */
  private Optional<Caller> caller(HttpServletRequest request, WhoAuthenticationToken token) {
    Identity identity = token.getPrincipal().identity();
    return switch (sessions.caller(request, token)) {
      case WhoCaller.Session session -> Optional.of(new Caller(identity, session));
      case WhoCaller.Stateless stateless when stateless.method() == SignInMethod.JWT ->
          Optional.of(new Caller(identity, null));
      case WhoCaller.Stateless _,
          WhoCaller.SessionWithForeignCredential _,
          WhoCaller.Other _,
          WhoCaller.SignedOut _ ->
          Optional.empty();
    };
  }

  /** A browser that signed in too long ago; a bearer token is never stale here. */
  private boolean stale(Caller caller) {
    return caller.browser() != null && !caller.browser().isRecent(recentSignIn, clock.instant());
  }

  /** The key the authentication itself names, never one the client names. */
  private void current(
      HttpServletRequest request, HttpServletResponse response, WhoAuthenticationToken token)
      throws IOException {
    Optional<ApiKey> key =
        token
            .signInMethod()
            .filter(SignInMethod.API_KEY::equals)
            .flatMap(method -> token.credentialId())
            .flatMap(id -> keys.findOwnedBy(token.getPrincipal().identity().id(), id));
    if (key.isEmpty()) {
      handoff.handOff(request, response, new ApiKeyNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    writeJson(response, HttpServletResponse.SC_OK, Listed.of(key.get()));
  }

  private void create(HttpServletRequest request, HttpServletResponse response, Identity identity)
      throws IOException {
    NewApiKey created;
    try {
      JsonNode body = readBody(request);
      String name = requiredString(body, "name");
      Duration lifetime = duration(requiredString(body, "lifetime"), "lifetime");
      created = keys.create(identity, name, lifetime, scopes(body));
    } catch (InvalidApiKeyRequestException e) {
      handoff.handOff(request, response, e, HttpStatus.BAD_REQUEST);
      return;
    } catch (IllegalArgumentException e) {
      handoff.handOff(
          request,
          response,
          new InvalidApiKeyRequestException(e.getMessage()),
          HttpStatus.BAD_REQUEST);
      return;
    }
    writeJson(response, HttpServletResponse.SC_CREATED, Created.of(created));
  }

  private void rotate(HttpServletRequest request, HttpServletResponse response, UUID identityId)
      throws IOException {
    Optional<UUID> keyId = owned(rotate, request, identityId);
    if (keyId.isEmpty()) {
      handoff.handOff(request, response, new ApiKeyNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    NewApiKey rotated;
    try {
      Duration overlap = duration(requiredString(readBody(request), "overlap"), "overlap");
      rotated = keys.rotate(keyId.get(), overlap);
    } catch (InvalidApiKeyRequestException e) {
      handoff.handOff(request, response, e, HttpStatus.BAD_REQUEST);
      return;
    } catch (IllegalArgumentException e) {
      // an overlap out of range, or a key that expired or was revoked meanwhile
      handoff.handOff(
          request,
          response,
          new InvalidApiKeyRequestException(e.getMessage()),
          HttpStatus.BAD_REQUEST);
      return;
    }
    writeJson(response, HttpServletResponse.SC_CREATED, Created.of(rotated));
  }

  private void revoke(HttpServletRequest request, HttpServletResponse response, UUID identityId)
      throws IOException {
    Optional<UUID> keyId = owned(revoke, request, identityId);
    if (keyId.isEmpty()) {
      handoff.handOff(request, response, new ApiKeyNotFoundException(), HttpStatus.NOT_FOUND);
      return;
    }
    keys.revoke(keyId.get());
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  /**
   * The id in the path, if it names one of the caller's keys: another identity's key, an unknown
   * one and a malformed id are all empty. The service checks no owner, so this is the check.
   */
  private Optional<UUID> owned(
      PathPatternRequestMatcher matcher, HttpServletRequest request, UUID identityId) {
    return parse(matcher.matcher(request).getVariables().get("id"))
        .filter(id -> keys.findOwnedBy(identityId, id).isPresent());
  }

  private static Optional<UUID> parse(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private JsonNode readBody(HttpServletRequest request) throws IOException {
    // The servlet container owns the request stream and closes it; who must not.
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new InvalidApiKeyRequestException("The body is too large");
    }
    byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new InvalidApiKeyRequestException("The body is too large");
    }
    JsonNode node;
    try {
      node = jsonMapper.readTree(bytes);
    } catch (JacksonException _) {
      throw new InvalidApiKeyRequestException("The body must be a JSON object");
    }
    if (node == null || !node.isObject()) {
      throw new InvalidApiKeyRequestException("The body must be a JSON object");
    }
    return node;
  }

  private static String requiredString(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isString()) {
      throw new InvalidApiKeyRequestException("The body needs " + field + " as a string");
    }
    return value.asString();
  }

  private static Duration duration(String value, String field) {
    try {
      return Duration.parse(value);
    } catch (DateTimeParseException _) {
      throw new InvalidApiKeyRequestException(
          field + " must be an ISO-8601 duration, such as P30D");
    }
  }

  private static Set<String> scopes(JsonNode body) {
    JsonNode value = body.get("scopes");
    if (value == null || !value.isArray()) {
      throw new InvalidApiKeyRequestException("The body needs scopes as an array of strings");
    }
    List<String> scopes = new ArrayList<>();
    for (JsonNode scope : value) {
      if (!scope.isString()) {
        throw new InvalidApiKeyRequestException("The body needs scopes as an array of strings");
      }
      scopes.add(scope.asString());
    }
    return Set.copyOf(scopes);
  }

  private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(jsonMapper.writeValueAsString(body));
  }

  /**
   * A key as the list and {@code /current} show it: no secret in it.
   *
   * @param id the key's id
   * @param name the key's name
   * @param scopes its scopes, sorted
   * @param createdAt when it was made
   * @param expiresAt when it stops, or stopped, signing in
   * @param lastUsedAt when it last signed in, to within the last-used interval; null if never
   */
  record Listed(
      String id,
      String name,
      List<String> scopes,
      String createdAt,
      String expiresAt,
      String lastUsedAt) {

    static Listed of(ApiKey key) {
      return new Listed(
          key.id().toString(),
          key.name(),
          key.scopes().stream().sorted().toList(),
          key.createdAt().toString(),
          key.expiresAt().toString(),
          key.lastUsedAt() == null ? null : key.lastUsedAt().toString());
    }
  }

  /**
   * A key just made or rotated, the only answer that holds the raw key.
   *
   * @param id the key's id
   * @param name the key's name
   * @param scopes its scopes, sorted
   * @param expiresAt when it stops signing in
   * @param key the raw key; never in {@link #toString()}
   */
  record Created(String id, String name, List<String> scopes, String expiresAt, String key) {

    static Created of(NewApiKey created) {
      return new Created(
          created.id().toString(),
          created.name(),
          created.scopes().stream().sorted().toList(),
          created.expiresAt().toString(),
          created.key());
    }

    /**
     * Describes the answer without the raw key, so it is safe to log.
     *
     * @return the id, name, scopes and expiry
     */
    @Override
    public String toString() {
      return "Created[id="
          + id
          + ", name="
          + name
          + ", scopes="
          + scopes
          + ", expiresAt="
          + expiresAt
          + "]";
    }
  }
}
