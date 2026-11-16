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
package org.jwcarman.who.examples.webandapi;

import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The app's two security chains, its only security code apart from {@code @RequiresPermission}.
 * Declaring them turns who's default chain off.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  /**
   * The API: access tokens and API keys, carried by each request, and nothing else. No session is
   * read or made, so a browser's session cookie signs nothing in here, and with nothing that rides
   * along by itself there is no CSRF to protect. A call without a token or key is {@code 401}.
   *
   * <p>It comes first: the web chain below matches every request, so a chain after it would never
   * see one.
   */
  @Bean
  @Order(1)
  SecurityFilterChain api(HttpSecurity http) {
    http.securityMatcher("/api/**")
        .with(WhoSecurity.stateless(), Customizer.withDefaults())
        .authorizeHttpRequests(a -> a.anyRequest().authenticated());
    return http.build();
  }

  /**
   * The website: sign in with an email link, a typed code or Keycloak, into a session, with CSRF
   * protection. The sign-in pages and their assets are open to signed-out visitors.
   */
  @Bean
  @Order(2)
  SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(publicPaths.patterns()).permitAll().anyRequest().authenticated());
    return http.build();
  }
}
