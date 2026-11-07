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
 * Entry points for applying who to a Spring Security chain.
 *
 * <pre>{@code
 * http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
 * }</pre>
 */
public final class WhoWebSecurity {

  private WhoWebSecurity() {}

  /**
   * Returns a configurer that makes a browser chain safe for session sign-in and applies every
   * {@link PasswordlessLoginMethod} bean.
   *
   * @return a new configurer
   */
  public static PasswordlessConfigurer passwordless() {
    return new PasswordlessConfigurer();
  }
}
