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
package org.jwcarman.who.admin;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the admin endpoints' JSON bodies, size-capped; anything who refuses is {@code 400}. */
final class AdminBodies {

  /** The largest body read, in bytes. */
  static final int MAX_BODY_BYTES = 8192;

  private AdminBodies() {}

  /**
   * Reads the body as a JSON object.
   *
   * @param jsonMapper reads the JSON
   * @param request the request
   * @return the object
   * @throws InvalidAdminRequestException if the body is larger than {@value #MAX_BODY_BYTES} bytes
   *     or is not a JSON object
   * @throws IOException if reading fails
   */
  static JsonNode readObject(JsonMapper jsonMapper, HttpServletRequest request) throws IOException {
    // The servlet container owns the request stream and closes it; who must not.
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      throw new InvalidAdminRequestException("The body is too large");
    }
    byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (bytes.length > MAX_BODY_BYTES) {
      throw new InvalidAdminRequestException("The body is too large");
    }
    JsonNode node;
    try {
      node = jsonMapper.readTree(bytes);
    } catch (JacksonException _) {
      throw new InvalidAdminRequestException("The body must be a JSON object");
    }
    if (node == null || !node.isObject()) {
      throw new InvalidAdminRequestException("The body must be a JSON object");
    }
    return node;
  }
}
