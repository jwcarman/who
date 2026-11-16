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
package org.jwcarman.who.examples.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.apikey.ApiKey;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.NewApiKey;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A person's API keys. {@link ApiKeyService} trusts its caller, so this controller decides who may
 * manage which key: only a person's token makes, lists or revokes keys, never a key, so a leaked
 * key cannot make itself permanent or broader, or map out its siblings; a key reads only itself;
 * and a key is revoked only by its owner.
 */
@RestController
class KeysController {

  /**
   * What a new key needs: who decides none of it for you.
   *
   * @param name what the key is for, such as the machine that holds it
   * @param lifetimeDays how long it works; at most {@code who.api-key.max-lifetime}
   * @param scopes the permissions it may use; each one the caller holds
   */
  record NewKey(String name, Integer lifetimeDays, Set<String> scopes) {}

  /**
   * A new key, the only time it is shown.
   *
   * @param id the key's id, to revoke it with
   * @param name what it is for
   * @param key the key itself: send it in {@code X-API-Key}
   * @param scopes the permissions it may use
   * @param expiresAt when it stops working
   */
  record CreatedKey(UUID id, String name, String key, Set<String> scopes, Instant expiresAt) {}

  private final ApiKeyService apiKeys;

  KeysController(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  @PostMapping("/api/keys")
  ResponseEntity<CreatedKey> create(@RequestBody NewKey request, Authentication authentication) {
    WhoPrincipal caller = personSignedInWithToken(authentication);
    // who refuses a blank name, a bad lifetime and a scope the caller lacks; a field left out of
    // the JSON is the app's to answer
    if (request.name() == null || request.lifetimeDays() == null || request.scopes() == null) {
      throw new IllegalArgumentException("A key needs a name, lifetimeDays and scopes");
    }
    NewApiKey created =
        apiKeys.create(
            caller.identity(),
            request.name(),
            Duration.ofDays(request.lifetimeDays()),
            request.scopes());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new CreatedKey(
                created.id(),
                created.name(),
                created.key(),
                created.scopes(),
                created.expiresAt()));
  }

  /** The caller's keys, with no secret in them: a person's, never a key's to see. */
  @GetMapping("/api/keys")
  List<ApiKey> list(Authentication authentication) {
    return apiKeys.findByIdentity(personSignedInWithToken(authentication).identity().id());
  }

  /**
   * The key this request signed in with, so a machine can see its own scopes and expiry, and rotate
   * in time, without seeing any other key. A request with no key has no self: {@code 404}.
   */
  @GetMapping("/api/keys/self")
  ApiKey self(Authentication authentication) {
    if (!(authentication instanceof WhoAuthenticationToken who)
        || who.signInMethod().filter(SignInMethod.API_KEY::equals).isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "This request did not use an API key");
    }
    // the id comes from the sign-in, never from the request
    UUID id =
        who.credentialId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key"));
    return apiKeys.findByIdentity(principal(authentication).identity().id()).stream()
        .filter(k -> k.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key"));
  }

  @DeleteMapping("/api/keys/{id}")
  ResponseEntity<Void> revoke(@PathVariable UUID id, Authentication authentication) {
    WhoPrincipal caller = personSignedInWithToken(authentication);
    // revoke takes only an id: check that it is the caller's, and answer another's as unknown
    boolean owned =
        apiKeys.findByIdentity(caller.identity().id()).stream().anyMatch(k -> k.id().equals(id));
    if (!owned) {
      return ResponseEntity.notFound().build();
    }
    apiKeys.revoke(id);
    return ResponseEntity.noContent().build();
  }

  /** A refused key request: what was wrong, never a secret. */
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> refused(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()));
  }

  private static WhoPrincipal personSignedInWithToken(Authentication authentication) {
    boolean token =
        authentication instanceof WhoAuthenticationToken who
            && who.signInMethod().filter(SignInMethod.JWT::equals).isPresent();
    if (!token) {
      throw new AccessDeniedException("Keys are made, listed and revoked with a person's token");
    }
    return principal(authentication);
  }

  private static WhoPrincipal principal(Authentication authentication) {
    return (WhoPrincipal) authentication.getPrincipal();
  }
}
