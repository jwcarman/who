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
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * Connects {@link SessionsController} to a browser chain: ending the caller's own session through
 * {@code DELETE /who/me/sessions/{id}} signs the browser out with that chain's logout handlers.
 * {@code WhoWebSecurity.passwordless()} applies it to every browser chain when this bean exists.
 * Not a way to sign in, so it does not make a chain a browser chain, and it adds no public path.
 */
public class SessionEndpoints {

  /** Creates the connection; it keeps no state. */
  public SessionEndpoints() {
    // stateless: configure adds the chain's logout to each request
  }

  /**
   * Hands the chain's logout handlers to each request it serves.
   *
   * @param http the chain
   * @param logout the chain's logout, whose handlers sign this browser out
   */
  void configure(HttpSecurity http, LogoutConfigurer<HttpSecurity> logout) {
    http.addFilterAfter(new ChainLogout(signOutHandler(logout)), ExceptionTranslationFilter.class);
  }

  /*
   * Signing out this browser through the endpoints runs the same handlers as the chain's logout:
   * who's, the app's own, CSRF's and cookie clearing, the context's, and LogoutSuccessEvent. The
   * chain adds the last two when it is built, so the handlers are read when a request needs them.
   * Should the app turn logout off, those two never come, so the context is always ended after
   * them too; ending it twice is harmless.
   */
  static LogoutHandler signOutHandler(LogoutConfigurer<HttpSecurity> logout) {
    LogoutHandler endContext = new SecurityContextLogoutHandler();
    return (request, response, authentication) -> {
      new CompositeLogoutHandler(logout.getLogoutHandlers())
          .logout(request, response, authentication);
      endContext.logout(request, response, authentication);
    };
  }
}
