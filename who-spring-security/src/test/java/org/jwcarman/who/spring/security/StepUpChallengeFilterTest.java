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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StepUpChallengeFilterTest {

  private static final String STEP_UP =
      "Bearer error=\"insufficient_user_authentication\", max_age=600";

  private final StepUpChallengeFilter filter = new StepUpChallengeFilter();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  /** What happens downstream of the filter: the request, and the response it was given. */
  @FunctionalInterface
  private interface Downstream {
    void handle(HttpServletRequest request, HttpServletResponse response) throws IOException;
  }

  private void run(Downstream downstream) throws Exception {
    filter.doFilter(
        request,
        response,
        (req, res) -> downstream.handle((HttpServletRequest) req, (HttpServletResponse) res));
  }

  @Test
  void a_refusal_answered_401_by_send_error_carries_the_challenge() throws Exception {
    run(
        (req, res) -> {
          StepUpChallenges.offer(req, res, STEP_UP);
          res.sendError(401);
        });

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly(STEP_UP);
  }

  @Test
  void a_refusal_answered_401_by_set_status_carries_the_challenge() throws Exception {
    run(
        (req, res) -> {
          StepUpChallenges.offer(req, res, STEP_UP);
          res.setStatus(401);
        });

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly(STEP_UP);
  }

  @Test
  void a_refusal_the_app_answers_otherwise_carries_no_challenge() throws Exception {
    run(
        (req, res) -> {
          StepUpChallenges.offer(req, res, STEP_UP);
          res.sendError(403, "advised");
        });

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
  }

  @Test
  void a_refusal_the_caller_recovers_from_carries_no_challenge() throws Exception {
    run(
        (req, res) -> {
          StepUpChallenges.offer(req, res, STEP_UP);
          res.setStatus(200);
        });

    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
  }

  @Test
  void a_401_with_no_refusal_gets_no_step_up_challenge() throws Exception {
    run((req, res) -> res.sendError(401, "who?"));

    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
  }

  @Test
  void a_response_who_does_not_watch_gets_the_challenge_at_once() {
    StepUpChallenges.offer(request, response, STEP_UP);

    assertThat(response.getHeaders(HttpHeaders.WWW_AUTHENTICATE)).containsExactly(STEP_UP);
  }
}
