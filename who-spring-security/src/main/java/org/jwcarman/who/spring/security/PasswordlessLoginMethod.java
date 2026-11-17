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

import java.util.Map;
import java.util.Set;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * A way to sign in to a browser session, contributed as a bean by a credential module (email links,
 * passkeys, ...). {@link WhoWebSecurity#passwordless()} and {@link WhoSecurity#all()} apply every
 * such bean, in {@code @Order} order, to the chain they configure.
 */
@FunctionalInterface
public interface PasswordlessLoginMethod {

  /**
   * Adds this login method to the chain.
   *
   * @param http the browser chain being built
   */
  void configure(HttpSecurity http);

  /**
   * Returns the paths a signed-out browser must reach to sign in this way, such as the endpoint
   * that sends a sign-in link. {@link WhoPublicPaths} collects them for the chain's {@code
   * permitAll()}.
   *
   * @return path patterns, empty by default
   */
  default Set<String> publicPaths() {
    return Set.of();
  }

  /**
   * Returns the paths whose POSTs the application's {@link WhoHumanCheck}, when it has one, must
   * pass first, each mapped to the action the check is told, such as {@code email-send}. Only
   * endpoints a signed-out caller can use to make the application do something costly belong here.
   *
   * @return full paths mapped to actions, empty by default
   */
  default Map<String, String> humanCheckedPaths() {
    return Map.of();
  }

  /**
   * Returns what {@code GET /who/sign-in} says about this method, for a sign-in page to draw its
   * choices from: a method that is on says so with a key such as {@code "email": true}. It is
   * public, so it must reveal nothing about any identity. {@link SignInOptionsFilter} merges every
   * method's answer and fills in {@code email} and {@code passkey} as {@code false} and {@code
   * providers} as an empty list when no method says otherwise.
   *
   * @return the options, empty by default
   */
  default Map<String, Object> signInOptions() {
    return Map.of();
  }

  /**
   * Returns this method's name, for the line who logs at startup naming the sign-in methods a chain
   * accepts. who's own methods return a short name such as {@code passkey}.
   *
   * @return the name; by default the class's simple name, or for an anonymous class or a lambda a
   *     name built from its enclosing class
   */
  default String name() {
    return LoginMethodNames.of(getClass());
  }
}
