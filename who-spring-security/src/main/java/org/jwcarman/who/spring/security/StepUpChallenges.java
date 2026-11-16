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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;

/**
 * Where a recent-sign-in refusal leaves its RFC 9470 step-up challenge. The refusal does not decide
 * the answer: the application's exception handling may answer {@code 401}, something else, or the
 * caller may catch the refusal and carry on. So on who's chains the challenge waits on the request,
 * and {@link StepUpChallengeFilter} sends it only with a {@code 401}. A response no who chain
 * watches gets it at once, as it always did.
 */
final class StepUpChallenges {

  private static final String WATCHED = StepUpChallenges.class.getName() + ".WATCHED";
  private static final String PENDING = StepUpChallenges.class.getName() + ".PENDING";

  private StepUpChallenges() {}

  /** Marks the request's response as one {@link StepUpChallengeFilter} watches. */
  static void watch(HttpServletRequest request) {
    request.setAttribute(WATCHED, Boolean.TRUE);
  }

  /** Offers the challenge for the request's answer. */
  static void offer(HttpServletRequest request, HttpServletResponse response, String challenge) {
    if (request.getAttribute(WATCHED) != null) {
      request.setAttribute(PENDING, challenge);
    } else if (!response.isCommitted()) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
    }
  }

  /** The challenge waiting for a {@code 401}, if any. */
  static String pending(HttpServletRequest request) {
    return request.getAttribute(PENDING) instanceof String challenge ? challenge : null;
  }
}
