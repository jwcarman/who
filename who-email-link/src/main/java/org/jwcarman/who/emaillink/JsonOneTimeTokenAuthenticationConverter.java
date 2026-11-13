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

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the body of {@code POST /login/ott}: either {@code {"token": "..."}} from the emailed link,
 * or {@code {"email": "...", "code": "..."}} typed by hand. Anything else, including a body with
 * both, yields {@link Malformed}, never {@code null} or an exception, so every sign-in failure
 * reaches the failure handler and gets the same answer. The code is passed on exactly as typed;
 * {@link EmailLinkAuthenticationProvider} normalizes it.
 */
public class JsonOneTimeTokenAuthenticationConverter implements AuthenticationConverter {

  private static final String TOKEN = "token";
  private static final String EMAIL = "email";
  private static final String CODE = "code";

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
    return new EmailSignInAuthenticationToken(
        proof(JsonBodies.readStrings(jsonMapper, request, TOKEN, EMAIL, CODE)));
  }

  private static EmailSignInProof proof(Map<String, String> fields) {
    // A field counts only if it is a non-blank string.
    String token = fields.get(TOKEN);
    String email = fields.get(EMAIL);
    String code = fields.get(CODE);
    boolean hasToken = isPresent(token);
    boolean hasEmail = isPresent(email);
    boolean hasCode = isPresent(code);
    if (hasToken && !hasEmail && !hasCode) {
      return new LinkToken(token);
    }
    if (!hasToken && hasEmail && hasCode) {
      return new EmailCode(email, code);
    }
    return new Malformed();
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
