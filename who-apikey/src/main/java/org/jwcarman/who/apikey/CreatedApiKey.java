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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * An API key just made or rotated: the only answer that holds the raw key.
 *
 * @param id the key's id
 * @param name the key's name
 * @param scopes its scopes, sorted
 * @param expiresAt when it stops signing in, ISO-8601
 * @param key the raw key; never in {@link #toString()}
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CreatedApiKey(
    String id, String name, List<String> scopes, String expiresAt, String key) {

  static CreatedApiKey of(NewApiKey created) {
    return new CreatedApiKey(
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
    return "CreatedApiKey[id="
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
