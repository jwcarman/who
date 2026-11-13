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

import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Adds the API key filter to a Spring Security chain. The filter is deliberately not registered
 * with the servlet container, so it only runs inside chains that ask for it:
 *
 * <pre>{@code
 * http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
 * }</pre>
 *
 * <p>The filter signs keys in through an authentication manager built as Spring builds its own:
 * with the application's {@code AuthenticationEventPublisher}, if it has one, and observed when the
 * application observes. A key that does not sign in is refused with {@code 401} through the
 * application's {@code WhoExceptionHandoff}, if it has one.
 */
public final class WhoApiKeySecurity {

  private WhoApiKeySecurity() {}

  /**
   * A configurer that adds the {@link ApiKeyAuthenticationFilter} bean from the application context
   * to the chain, before the authorization filter.
   *
   * @return a configurer for {@code HttpSecurity.with(...)}
   */
  public static ApiKeyConfigurer apiKey() {
    return new ApiKeyConfigurer(null);
  }

  /**
   * A configurer that adds the given filter to the chain, before the authorization filter.
   *
   * @param filter the API key filter
   * @return a configurer for {@code HttpSecurity.with(...)}
   */
  public static ApiKeyConfigurer apiKey(ApiKeyAuthenticationFilter filter) {
    return new ApiKeyConfigurer(requireNonNull(filter, "filter must not be null"));
  }

  /** Configurer that places the API key filter in an {@link HttpSecurity} chain. */
  public static final class ApiKeyConfigurer
      extends AbstractHttpConfigurer<ApiKeyConfigurer, HttpSecurity> {

    private final ApiKeyAuthenticationFilter filter;

    private ApiKeyConfigurer(ApiKeyAuthenticationFilter filter) {
      this.filter = filter;
    }

    @Override
    public void configure(HttpSecurity http) {
      ApplicationContext context = http.getSharedObject(ApplicationContext.class);
      ApiKeyAuthenticationFilter toAdd =
          filter == null ? context.getBean(ApiKeyAuthenticationFilter.class) : filter;
      if (context != null) {
        toAdd.applyApplicationContext(context);
      }
      http.addFilterBefore(toAdd, AuthorizationFilter.class);
    }
  }
}
