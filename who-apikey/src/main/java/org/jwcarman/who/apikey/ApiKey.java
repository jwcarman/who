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

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * An identity's key as {@link ApiKeyService#findByIdentity} lists it, with no secret in it.
 *
 * @param id the key's credential id
 * @param name the key's name
 * @param scopes the permissions the key may use, at most
 * @param createdAt when the key was made
 * @param expiresAt when the key stops, or stopped, signing in
 * @param lastUsedAt when it last signed in, to within the last-used interval; {@code null} when it
 *     never has
 * @param expired whether {@code expiresAt} had passed when the list was made
 */
public record ApiKey(
    UUID id,
    String name,
    Set<String> scopes,
    Instant createdAt,
    Instant expiresAt,
    Instant lastUsedAt,
    boolean expired) {

  /** Copies the scopes, so the list cannot change them. */
  public ApiKey {
    scopes = Set.copyOf(scopes);
  }
}
