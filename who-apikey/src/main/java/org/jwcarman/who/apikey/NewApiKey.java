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

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A key just made, by {@link ApiKeyService#create} or {@link ApiKeyService#rotate}: the only time
 * the raw key exists outside the caller. Show it once; who keeps only its hash.
 *
 * @param id the key's credential id, to list, rotate or revoke it by
 * @param name the key's name
 * @param key the raw key, sent in the API-key header; never in {@link #toString()}
 * @param scopes the permissions the key may use, at most
 * @param expiresAt when the key stops signing in
 */
public record NewApiKey(UUID id, String name, String key, Set<String> scopes, Instant expiresAt) {

  /** Validates that no component is {@code null}. */
  public NewApiKey {
    requireNonNull(id, "id must not be null");
    requireNonNull(name, "name must not be null");
    requireNonNull(key, "key must not be null");
    scopes = Set.copyOf(requireNonNull(scopes, "scopes must not be null"));
    requireNonNull(expiresAt, "expiresAt must not be null");
  }

  /**
   * Describes the key without the raw key, so it is safe to log.
   *
   * @return the id, name, scopes and expiry
   */
  @Override
  public String toString() {
    return "NewApiKey[id="
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
