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
 * kind yields a token for {@link #NO_TOKEN}, never {@code null} or an exception, so every sign-in
 * failure reaches the failure handler and gets the same answer.
 */
public class JsonOneTimeTokenAuthenticationConverter implements AuthenticationConverter {

  /**
   * The token value used for malformed requests. Returning {@code null} would make Spring skip the
   * failure handler, so this stands in for "no token"; {@link EmailLinkAuthenticationProvider}
   * rejects it as a failed sign-in without consuming anything. Real tokens are 43 characters, so
   * this can never be one.
   */
  public static final String NO_TOKEN = "-";

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
      return new OneTimeTokenAuthenticationToken(NO_TOKEN);
    }
    return new OneTimeTokenAuthenticationToken(token);
  }
}
