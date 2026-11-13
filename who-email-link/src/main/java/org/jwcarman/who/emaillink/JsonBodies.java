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
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads string fields from a JSON request body without ever throwing. */
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
    return readStrings(jsonMapper, request, field).get(field);
  }

  /**
   * Reads several string fields from the request's JSON object body in one pass.
   *
   * @param jsonMapper the JSON mapper
   * @param request the request; its body is read once
   * @param fields the field names
   * @return the requested fields whose values are JSON strings; empty if the body is not a JSON
   *     object
   */
  static Map<String, String> readStrings(
      JsonMapper jsonMapper, HttpServletRequest request, String... fields) {
    // The servlet container owns the request stream and closes it; who must not.
    try {
      InputStream body = request.getInputStream();
      if (body == null) {
        return Map.of();
      }
      JsonNode node = jsonMapper.readTree(body);
      if (node == null || !node.isObject()) {
        return Map.of();
      }
      Map<String, String> values = new HashMap<>();
      for (String field : fields) {
        JsonNode value = node.get(field);
        if (value != null && value.isString()) {
          values.put(field, value.asString());
        }
      }
      return Map.copyOf(values);
    } catch (IOException | RuntimeException _) {
      return Map.of();
    }
  }
}
