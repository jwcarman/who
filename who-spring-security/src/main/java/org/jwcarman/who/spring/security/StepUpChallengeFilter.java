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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sends a recent-sign-in refusal's step-up challenge only when the response turns out to be a
 * {@code 401}, whoever writes it: who, the application's exception handling, or Spring MVC. A
 * {@code 403} from the application, or a {@code 200} from a caller that caught the refusal, gets no
 * {@code WWW-Authenticate} header that contradicts it.
 */
final class StepUpChallengeFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    StepUpChallenges.watch(request);
    chain.doFilter(request, new ChallengeOn401(request, response));
  }

  /** Adds the pending challenge as the status becomes {@code 401}, before anything is sent. */
  private static final class ChallengeOn401 extends HttpServletResponseWrapper {

    private final HttpServletRequest request;

    private ChallengeOn401(HttpServletRequest request, HttpServletResponse response) {
      super(response);
      this.request = request;
    }

    @Override
    public void setStatus(int status) {
      challenge(status);
      super.setStatus(status);
    }

    @Override
    public void sendError(int status) throws IOException {
      challenge(status);
      super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
      challenge(status);
      super.sendError(status, message);
    }

    /**
     * Adds the challenge beside any other scheme's, unless the answer already challenges with
     * Bearer: an application that wrote its own (a realm, a scope) keeps it.
     */
    private void challenge(int status) {
      String challenge = StepUpChallenges.pending(request);
      if (status == HttpServletResponse.SC_UNAUTHORIZED
          && challenge != null
          && !isCommitted()
          && getHeaders(HttpHeaders.WWW_AUTHENTICATE).stream()
              .noneMatch(present -> StatelessAnswers.sameScheme(present, challenge))) {
        addHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
      }
    }
  }
}
