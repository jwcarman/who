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

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * An API key as {@code GET /who/me/api-keys} and {@code GET /who/me/api-keys/current} show it: no
 * secret in it.
 *
 * @param id the key's id
 * @param name the key's name
 * @param scopes its scopes, sorted
 * @param createdAt when it was made, ISO-8601
 * @param expiresAt when it stops, or stopped, signing in, ISO-8601
 * @param lastUsedAt when it last signed in, to within the last-used interval; {@code null} if never
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiKeyView(
    String id,
    String name,
    List<String> scopes,
    String createdAt,
    String expiresAt,
    @Nullable String lastUsedAt) {

  static ApiKeyView of(ApiKey key) {
    return new ApiKeyView(
        key.id().toString(),
        key.name(),
        key.scopes().stream().sorted().toList(),
        key.createdAt().toString(),
        key.expiresAt().toString(),
        key.lastUsedAt() == null ? null : key.lastUsedAt().toString());
  }
}
