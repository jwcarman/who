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
 * Endpoints that a module adds to every browser chain for a signed-in person, such as changing an
 * email address. Not a way to sign in: {@link WhoWebSecurity#passwordless()} applies each bean
 * after the chain's {@link PasswordlessLoginMethod}s, and never names it in the startup line.
 */
@FunctionalInterface
public interface BrowserEndpoints {

  /**
   * Adds these endpoints to the chain.
   *
   * @param http the browser chain being built
   */
  void configure(HttpSecurity http);
}
