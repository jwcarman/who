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

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hands a browser chain's logout handlers to the request, so {@link SessionsController} signs a
 * browser out the way the chain's own logout does: who's handlers, the application's, CSRF's,
 * cookie clearing and {@code LogoutSuccessEvent}. A controller cannot otherwise see which chain
 * served it.
 */
final class ChainLogout extends OncePerRequestFilter {

  /** The request attribute holding the chain's {@link LogoutHandler}. */
  static final String ATTRIBUTE = ChainLogout.class.getName();

  private final LogoutHandler handler;

  ChainLogout(LogoutHandler handler) {
    this.handler = requireNonNull(handler, "handler must not be null");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    request.setAttribute(ATTRIBUTE, handler);
    chain.doFilter(request, response);
  }
}
