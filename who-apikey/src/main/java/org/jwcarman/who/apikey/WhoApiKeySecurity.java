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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Adds the API key filter to a Spring Security chain. The filter is deliberately not registered
 * with the servlet container, so it only runs inside chains that ask for it.
 */
public final class WhoApiKeySecurity {

  private WhoApiKeySecurity() {}

  /**
   * Adds the {@link ApiKeyAuthenticationFilter} bean from the application context to the chain,
   * before the authorization filter.
   *
   * @return a customizer for {@link HttpSecurity}
   */
  public static Customizer<HttpSecurity> apiKey() {
    return http -> {
      ApplicationContext context = http.getSharedObject(ApplicationContext.class);
      apiKey(context.getBean(ApiKeyAuthenticationFilter.class)).customize(http);
    };
  }

  /**
   * Adds the given filter to the chain, before the authorization filter.
   *
   * @param filter the API key filter
   * @return a customizer for {@link HttpSecurity}
   */
  public static Customizer<HttpSecurity> apiKey(ApiKeyAuthenticationFilter filter) {
    requireNonNull(filter, "filter must not be null");
    return http -> http.addFilterBefore(filter, AuthorizationFilter.class);
  }
}
