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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.web.authentication.ott.GenerateOneTimeTokenRequestResolver;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code {"email": "..."}} from the body of {@code POST /ott/generate}.
 *
 * <p>Malformed input of any kind never throws and never yields {@code null} (which would make
 * Spring skip the success handler and answer differently): it yields a request for {@link
 * #NO_ADDRESS}, which {@link EmailLinkIssuer} ignores, so the caller still gets {@code 202}.
 */
public class JsonGenerateOneTimeTokenRequestResolver
    implements GenerateOneTimeTokenRequestResolver {

  /**
   * The username used for malformed requests. Spring rejects a blank username, so this stands in
   * for "no address"; it contains no {@code @}, so it can never be a registered address.
   */
  public static final String NO_ADDRESS = "-";

  private static final Logger log =
      LoggerFactory.getLogger(JsonGenerateOneTimeTokenRequestResolver.class);

  private final JsonMapper jsonMapper;

  /**
   * Creates a new resolver.
   *
   * @param jsonMapper the JSON mapper
   */
  public JsonGenerateOneTimeTokenRequestResolver(JsonMapper jsonMapper) {
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
  }

  @Override
  public GenerateOneTimeTokenRequest resolve(HttpServletRequest request) {
    String email = JsonBodies.readString(jsonMapper, request, "email");
    if (email == null || email.isBlank()) {
      log.debug("Malformed one-time token generation request");
      return new GenerateOneTimeTokenRequest(NO_ADDRESS);
    }
    return new GenerateOneTimeTokenRequest(email);
  }
}
