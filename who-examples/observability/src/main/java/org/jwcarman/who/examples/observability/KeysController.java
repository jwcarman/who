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
package org.jwcarman.who.examples.observability;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.NewApiKey;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.spring.security.RequiresRecentSignIn;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The page's keys: a person signed in by email makes one, scoped to reading the greeting only, so
 * writing with it is a 403 to see on the dashboard. who keeps only the key's hash.
 */
@RestController
class KeysController {

  /**
   * A key, shown this once.
   *
   * @param id the key's id, to revoke it
   * @param key the key itself
   */
  record MadeKey(UUID id, String key) {}

  private final ApiKeyService apiKeys;

  KeysController(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  /** Makes a key; a key outlives the session, so this needs a sign-in in the last ten minutes. */
  @PostMapping("/keys")
  @RequiresRecentSignIn
  @ResponseStatus(HttpStatus.CREATED)
  MadeKey make(@AuthenticationPrincipal WhoPrincipal principal) {
    NewApiKey key =
        apiKeys.create(
            principal.identity(), "page", Duration.ofDays(1), Set.of(Permissions.GREETING_READ));
    return new MadeKey(key.id(), key.key());
  }

  /** Revokes one of the caller's keys; anyone else's, or an unknown id, is 404. */
  @DeleteMapping("/keys/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID id, @AuthenticationPrincipal WhoPrincipal principal) {
    boolean owned =
        apiKeys.findByIdentity(principal.identity().id()).stream().anyMatch(k -> k.id().equals(id));
    if (!owned) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key");
    }
    apiKeys.revoke(id);
  }
}
