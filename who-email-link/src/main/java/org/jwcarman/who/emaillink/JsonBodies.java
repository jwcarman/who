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

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads a single string field from a JSON request body without ever throwing. */
final class JsonBodies {

  private JsonBodies() {
    // utility class
  }

  /**
   * Reads a string field from the request's JSON object body.
   *
   * @param jsonMapper the JSON mapper
   * @param request the request; its body is read once
   * @param field the field name
   * @return the field's value, or {@code null} if the body is not a JSON object with that field as
   *     a string
   */
  static String readString(JsonMapper jsonMapper, HttpServletRequest request, String field) {
    try (InputStream body = request.getInputStream()) {
      if (body == null) {
        return null;
      }
      JsonNode node = jsonMapper.readTree(body);
      if (node == null || !node.isObject()) {
        return null;
      }
      JsonNode value = node.get(field);
      return value != null && value.isString() ? value.asString() : null;
    } catch (IOException | RuntimeException _) {
      return null;
    }
  }
}
