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

import static java.util.Objects.requireNonNull;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code {"token": "..."}} from the body of {@code POST /login/ott}. Malformed input of any
 * kind yields {@code null}, never an exception.
 */
public class JsonOneTimeTokenAuthenticationConverter implements AuthenticationConverter {

  private final JsonMapper jsonMapper;

  /**
   * Creates a new converter.
   *
   * @param jsonMapper the JSON mapper
   */
  public JsonOneTimeTokenAuthenticationConverter(JsonMapper jsonMapper) {
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  @Override
  public Authentication convert(HttpServletRequest request) {
    String token = JsonBodies.readString(jsonMapper, request, "token");
    if (token == null || token.isBlank()) {
      return null;
    }
    return OneTimeTokenAuthenticationToken.unauthenticated(token);
  }
}
