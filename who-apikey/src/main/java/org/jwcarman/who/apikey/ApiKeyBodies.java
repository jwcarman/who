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

import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the API key endpoints' request bodies; anything who refuses is {@code 400}. */
final class ApiKeyBodies {

  /** The largest body read, in bytes. */
  static final int MAX_BODY_BYTES = 8192;

  private ApiKeyBodies() {}

  /**
   * The body as a JSON object, read up to {@link #MAX_BODY_BYTES}.
   *
   * @throws InvalidApiKeyRequestException if it is larger, or not a JSON object
   */
  static JsonNode read(JsonMapper jsonMapper, HttpServletRequest request) throws IOException {
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

  static String requiredString(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.isString()) {
      throw new InvalidApiKeyRequestException("The body needs " + field + " as a string");
    }
    return value.asString();
  }

  static Duration duration(String value, String field) {
    try {
      return Duration.parse(value);
    } catch (DateTimeParseException _) {
      throw new InvalidApiKeyRequestException(
          field + " must be an ISO-8601 duration, such as P30D");
    }
  }

  static Set<String> scopes(JsonNode body) {
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
}
