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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HumanCheckFilterTest {

  private static final Map<String, String> ACTIONS =
      Map.of("/who/email/send", "email-send", "/who/email/sign-in", "email-sign-in");

  private final List<HumanCheckRequest> seen = new ArrayList<>();

  private MockHttpServletResponse run(WhoHumanCheck check, MockHttpServletRequest request)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    new HumanCheckFilter(
            check, new WhoExceptionHandoff(null), PathPatternRequestMatcher.withDefaults(), ACTIONS)
        .doFilter(request, response, chain);
    reached = chain.getRequest() != null;
    return response;
  }

  private boolean reached;

  private WhoHumanCheck answering(boolean answer) {
    return request -> {
      seen.add(request);
      return answer;
    };
  }

  private static MockHttpServletRequest post(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr("203.0.113.7");
    return request;
  }

  @Test
  void a_passing_check_reaches_the_endpoint() throws Exception {
    MockHttpServletRequest request = post("/who/email/send");
    request.addHeader(WhoHumanCheck.HEADER, "tok");

    run(answering(true), request);

    assertThat(reached).isTrue();
    assertThat(seen)
        .containsExactly(new HumanCheckRequest(Optional.of("tok"), "203.0.113.7", "email-send"));
  }

  @Test
  void the_sign_in_endpoint_is_checked_with_its_own_action() throws Exception {
    run(answering(true), post("/who/email/sign-in"));

    assertThat(seen).extracting(HumanCheckRequest::action).containsExactly("email-sign-in");
  }

  @Test
  void a_failing_check_refuses_with_403_and_the_code() throws Exception {
    MockHttpServletResponse response = run(answering(false), post("/who/email/send"));

    assertThat(reached).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(WhoExceptionHandoff.HEADER)).isEqualTo("human-check-failed");
  }

  @Test
  void a_check_that_throws_refuses() throws Exception {
    WhoHumanCheck broken =
        request -> {
          throw new IllegalStateException("vendor down");
        };

    MockHttpServletResponse response = run(broken, post("/who/email/send"));

    assertThat(reached).isFalse();
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(WhoExceptionHandoff.HEADER)).isEqualTo("human-check-failed");
  }

  @Test
  void a_missing_header_gives_an_empty_token() throws Exception {
    run(answering(true), post("/who/email/send"));

    assertThat(seen).extracting(HumanCheckRequest::token).containsExactly(Optional.empty());
  }

  @Test
  void a_blank_header_gives_an_empty_token() throws Exception {
    MockHttpServletRequest request = post("/who/email/send");
    request.addHeader(WhoHumanCheck.HEADER, "  ");

    run(answering(true), request);

    assertThat(seen).extracting(HumanCheckRequest::token).containsExactly(Optional.empty());
  }

  @Test
  void the_check_runs_only_on_checked_paths() throws Exception {
    run(answering(false), post("/who/me"));
    assertThat(reached).isTrue();
    run(answering(false), new MockHttpServletRequest("GET", "/who/email/send"));
    assertThat(reached).isTrue();

    assertThat(seen).isEmpty();
  }

  @Test
  void allow_all_passes_everyone() {
    assertThat(
            WhoHumanCheck.ALLOW_ALL.passes(
                new HumanCheckRequest(Optional.empty(), "203.0.113.7", "email-send")))
        .isTrue();
  }

  @Test
  void a_request_needs_its_parts() {
    assertThatNullPointerException()
        .isThrownBy(() -> new HumanCheckRequest(null, "203.0.113.7", "email-send"));
    assertThatNullPointerException()
        .isThrownBy(() -> new HumanCheckRequest(Optional.empty(), null, "email-send"));
    assertThatNullPointerException()
        .isThrownBy(() -> new HumanCheckRequest(Optional.empty(), "203.0.113.7", null));
  }
}
