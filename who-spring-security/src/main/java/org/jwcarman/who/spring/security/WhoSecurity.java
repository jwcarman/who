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

/**
 * Entry point for mounting who into a Spring Security chain the application writes itself.
 *
 * <pre>{@code
 * @Bean
 * SecurityFilterChain chain(HttpSecurity http, WhoPublicPaths publicPaths) {
 *   http.with(WhoSecurity.all(), Customizer.withDefaults())
 *       .authorizeHttpRequests(a -> a
 *           .requestMatchers(publicPaths.patterns()).permitAll()
 *           .anyRequest().authenticated());
 *   return http.build();
 * }
 * }</pre>
 *
 * <p>Without a chain of the application's own, who's autoconfiguration registers exactly this
 * chain. For finer control, mount methods one at a time with {@link WhoWebSecurity#passwordless()}
 * or a module's own configurer.
 */
public final class WhoSecurity {

  private WhoSecurity() {}

  /**
   * Returns a configurer that mounts every who sign-in method on the classpath.
   *
   * @return a new configurer
   */
  public static WhoSecurityConfigurer all() {
    return new WhoSecurityConfigurer();
  }

  /**
   * Returns a configurer that mounts only who's stateless sign-in methods (API keys, bearer JWTs)
   * into a chain with no session and no CSRF, answering a signed-out request with {@code 401}. It
   * gives an API a chain of its own beside a browser chain:
   *
   * <pre>{@code
   * @Bean @Order(1)
   * SecurityFilterChain api(HttpSecurity http) {
   *   http.securityMatcher("/api/**")
   *       .with(WhoSecurity.stateless(), Customizer.withDefaults())
   *       .authorizeHttpRequests(a -> a.anyRequest().authenticated());
   *   return http.build();
   * }
   *
   * @Bean @Order(2)
   * SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
   *   http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults())
   *       .authorizeHttpRequests(a -> a
   *           .requestMatchers(publicPaths.patterns()).permitAll()
   *           .anyRequest().authenticated());
   *   return http.build();
   * }
   * }</pre>
   *
   * <p>A browser's session cookie signs nothing in on the API chain.
   *
   * @return a new configurer
   * @throws IllegalStateException when the chain is built, if no stateless method is on
   */
  public static WhoStatelessConfigurer stateless() {
    return new WhoStatelessConfigurer();
  }
}
