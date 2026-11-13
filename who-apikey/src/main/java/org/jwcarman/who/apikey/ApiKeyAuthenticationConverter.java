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

import static java.util.Objects.requireNonNull;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Reads an API key from a request header into an {@link ApiKeyAuthenticationToken}.
 *
 * <p>Returns {@code null}, so the request goes on without signing in, when the header is absent or
 * blank, or when the request is already authenticated by anything other than an anonymous
 * authentication: a key never replaces an existing sign-in.
 */
public class ApiKeyAuthenticationConverter implements AuthenticationConverter {

  private final String headerName;

  /**
   * Creates a converter for the given header.
   *
   * @param headerName HTTP header name that carries the API key
   */
  public ApiKeyAuthenticationConverter(String headerName) {
    this.headerName = requireNonNull(headerName, "headerName must not be null");
  }

  @Override
  public ApiKeyAuthenticationToken convert(HttpServletRequest request) {
    String key = request.getHeader(headerName);
    if (key == null || key.isBlank() || alreadyAuthenticated()) {
      return null;
    }
    return new ApiKeyAuthenticationToken(key);
  }

  private static boolean alreadyAuthenticated() {
    Authentication existing = SecurityContextHolder.getContext().getAuthentication();
    return existing != null && !(existing instanceof AnonymousAuthenticationToken);
  }
}
