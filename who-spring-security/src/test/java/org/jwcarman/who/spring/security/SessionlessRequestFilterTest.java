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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SessionlessRequestFilterTest {

  @ParameterizedTest
  @EnumSource(
      value = DispatcherType.class,
      names = {"REQUEST", "ERROR", "ASYNC"})
  void the_chain_sees_no_session(DispatcherType dispatch) throws Exception {
    HttpServletRequest seen = filtered(dispatch);

    assertThat(seen.getSession(false)).isNull();
    assertThat(seen.getRequestedSessionId()).isNull();
    assertThat(seen.isRequestedSessionIdValid()).isFalse();
    assertThat(seen.isRequestedSessionIdFromCookie()).isFalse();
    assertThat(seen.isRequestedSessionIdFromURL()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = DispatcherType.class,
      names = {"REQUEST", "ERROR", "ASYNC"})
  void nothing_on_the_chain_may_make_or_rotate_one(DispatcherType dispatch) throws Exception {
    HttpServletRequest seen = filtered(dispatch);

    assertThatThrownBy(seen::getSession)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(SessionlessRequestFilter.NO_SESSIONS);
    assertThatThrownBy(() -> seen.getSession(true)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(seen::changeSessionId).isInstanceOf(IllegalStateException.class);
  }

  private static HttpServletRequest filtered(DispatcherType dispatch) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
    request.setSession(new MockHttpSession());
    request.setRequestedSessionId("browser-session");
    request.setRequestedSessionIdFromCookie(true);
    request.setDispatcherType(dispatch);
    MockFilterChain chain = new MockFilterChain();

    new SessionlessRequestFilter().doFilter(request, new MockHttpServletResponse(), chain);

    return (HttpServletRequest) chain.getRequest();
  }
}
