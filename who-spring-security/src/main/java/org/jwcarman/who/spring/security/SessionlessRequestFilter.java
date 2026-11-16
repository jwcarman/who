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

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hides the session from the rest of a {@link WhoSecurity#stateless()} chain: a session the request
 * brings is not there, so nothing on the chain can sign in from it, give it a new id, or end it.
 * Asking to create one fails, as nothing on a stateless chain may.
 */
final class SessionlessRequestFilter extends OncePerRequestFilter {

  static final String NO_SESSIONS =
      "A WhoSecurity.stateless() chain has no sessions; nothing on it may create one";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(new Sessionless(request), response);
  }

  private static final class Sessionless extends HttpServletRequestWrapper {

    Sessionless(HttpServletRequest request) {
      super(request);
    }

    @Override
    public HttpSession getSession(boolean create) {
      if (create) {
        throw new IllegalStateException(NO_SESSIONS);
      }
      return null;
    }

    @Override
    public HttpSession getSession() {
      return getSession(true);
    }

    @Override
    public String changeSessionId() {
      throw new IllegalStateException(NO_SESSIONS);
    }

    @Override
    public String getRequestedSessionId() {
      return null;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
      return false;
    }
  }
}
