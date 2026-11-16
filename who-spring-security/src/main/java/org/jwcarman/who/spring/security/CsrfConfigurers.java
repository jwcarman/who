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
package org.jwcarman.who.spring.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;

/** Finds a chain's CSRF configuration without turning CSRF back on. */
final class CsrfConfigurers {

  private CsrfConfigurers() {}

  /**
   * The chain's CSRF configurer, or {@code null} when the chain turned CSRF off. {@code
   * http.csrf(...)} cannot answer this: it turns CSRF back on.
   */
  // Spring Security's contract: getConfigurer(Class<C>) needs C to be CsrfConfigurer<HttpSecurity>,
  // and Java has only the raw class literal CsrfConfigurer.class, so the lookup is an unchecked
  // invocation however it is written. Spring Security suppresses the same warning internally.
  @SuppressWarnings("unchecked")
  static @Nullable CsrfConfigurer<HttpSecurity> of(HttpSecurity http) {
    return http.getConfigurer(CsrfConfigurer.class);
  }
}
