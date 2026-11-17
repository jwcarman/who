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

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Endpoints that a module adds to every stateless chain for a caller signed in by a credential the
 * request carries, such as managing the caller's API keys with a bearer token. Not a way to sign
 * in: {@link WhoSecurity#stateless()}, and {@link WhoSecurity#all()} on a chain with no browser
 * sign-in method, apply each bean after the chain's {@link StatelessLoginMethod}s, and never name
 * it in the startup line. A chain that signs browsers in applies the {@link BrowserEndpoints}
 * instead, so a module whose endpoints serve both kinds of chain registers the same endpoints as
 * both.
 */
@FunctionalInterface
public interface StatelessEndpoints {

  /**
   * Adds these endpoints to the chain.
   *
   * @param http the stateless chain being built
   */
  void configure(HttpSecurity http);
}
