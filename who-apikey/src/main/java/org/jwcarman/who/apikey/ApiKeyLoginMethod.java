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
import static org.springframework.security.config.Customizer.withDefaults;

import org.jwcarman.who.spring.security.StatelessLoginMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * API-key sign-in as a {@link StatelessLoginMethod}: adds the {@link ApiKeyAuthenticationFilter} as
 * {@link WhoApiKeySecurity#apiKey(ApiKeyAuthenticationFilter)} does, and matches the requests the
 * key would sign in: the header is present and not blank, and the request is not already signed in.
 * A request already signed in from a session ignores the header, as {@link
 * ApiKeyAuthenticationConverter} does, so the header does not exempt it from CSRF either. (On a
 * {@code WhoSecurity.stateless()} chain no session signs the request in, so only another credential
 * on the same request, such as a bearer token, makes the key ignored.)
 */
public final class ApiKeyLoginMethod implements StatelessLoginMethod {

  private final ApiKeyAuthenticationFilter filter;
  private final ApiKeyAuthenticationConverter converter;

  /**
   * Creates the login method.
   *
   * @param filter the filter that signs a key in
   * @param headerName the header the filter reads the key from
   */
  public ApiKeyLoginMethod(ApiKeyAuthenticationFilter filter, String headerName) {
    this.filter = requireNonNull(filter, "filter must not be null");
    this.converter =
        new ApiKeyAuthenticationConverter(
            requireNonNull(headerName, "headerName must not be null"));
  }

  @Override
  public void configure(HttpSecurity http) {
    http.with(WhoApiKeySecurity.apiKey(filter), withDefaults());
  }

  @Override
  public RequestMatcher carriesCredential() {
    return request -> converter.convert(request) != null;
  }
}
