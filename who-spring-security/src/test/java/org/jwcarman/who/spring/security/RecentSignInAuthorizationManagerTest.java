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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.web.InsufficientUserAuthenticationException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.RecentSignInUnavailableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RecentSignInAuthorizationManagerTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(10);
  private static final String STEP_UP =
      "Bearer error=\"insufficient_user_authentication\", "
          + "error_description=\"A more recent sign-in is required\", max_age=600";

  private final RecentSignInAuthorizationManager manager =
      new RecentSignInAuthorizationManager(WINDOW, Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static WhoAuthenticationToken signedIn(SignInMethod method, Instant at) {
    return WhoAuthenticationToken.signedIn(principal(), UUID.randomUUID(), method, at);
  }

  private static WhoPrincipal principal() {
    return new WhoPrincipal(Identity.create(), Set.of("a"));
  }

  /** A token rebuilt from a session: no sign-in method, only the factor. */
  private static WhoAuthenticationToken session(Instant signedInAt) {
    return new WhoAuthenticationToken(
        principal(),
        UUID.randomUUID(),
        null,
        WhoSignInFactors.of(SignInMethod.EMAIL_CODE, signedInAt).orElseThrow());
  }

  private RecentSignInDecision decide(Authentication authentication) {
    return manager.authorize(() -> authentication, new Object());
  }

  @Test
  void a_recent_session_is_granted() {
    RecentSignInDecision decision = decide(session(NOW.minusSeconds(60)));

    assertThat(decision.isGranted()).isTrue();
    assertThat(decision.refusal()).isNull();
    assertThat(decision.challenge()).isEmpty();
  }

  @Test
  void a_stale_session_must_sign_in_again() {
    RecentSignInDecision decision = decide(session(NOW.minus(WINDOW).minusSeconds(1)));

    assertThat(decision.isGranted()).isFalse();
    assertThat(decision.refusal())
        .isInstanceOf(ReauthenticationRequiredException.class)
        .isNotInstanceOf(AccessDeniedException.class);
    assertThat(decision.challenge()).isEmpty();
  }

  @Test
  void a_session_without_a_factor_must_sign_in_again() {
    RecentSignInDecision decision =
        decide(new WhoAuthenticationToken(principal(), UUID.randomUUID()));

    assertThat(decision.refusal()).isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void every_sign_in_method_counts() {
    for (SignInMethod method :
        new SignInMethod[] {
          SignInMethod.EMAIL_LINK, SignInMethod.EMAIL_CODE, SignInMethod.PASSKEY, SignInMethod.OIDC
        }) {
      assertThat(decide(signedIn(method, NOW)).isGranted()).as(method.name()).isTrue();
      assertThat(decide(signedIn(method, NOW.minus(WINDOW).minusSeconds(1))).refusal())
          .as(method.name())
          .isInstanceOf(ReauthenticationRequiredException.class);
    }
  }

  @Test
  void a_recent_jwt_is_granted() {
    assertThat(decide(signedIn(SignInMethod.JWT, NOW.minusSeconds(60))).isGranted()).isTrue();
  }

  @Test
  void a_stale_jwt_gets_the_step_up_challenge() {
    RecentSignInDecision decision =
        decide(signedIn(SignInMethod.JWT, NOW.minus(WINDOW).minusSeconds(1)));

    assertThat(decision.refusal())
        .isInstanceOfSatisfying(
            InsufficientUserAuthenticationException.class,
            e -> {
              assertThat(e.maxAge()).isEqualTo(WINDOW);
              assertThat(e.challenge()).isEqualTo(STEP_UP);
            });
    assertThat(decision.challenge()).contains(STEP_UP);
  }

  @Test
  void a_jwt_without_auth_time_is_forbidden() {
    RecentSignInDecision decision =
        decide(new WhoAuthenticationToken(principal(), UUID.randomUUID(), SignInMethod.JWT));

    assertThat(decision.refusal())
        .isInstanceOf(RecentSignInUnavailableException.class)
        .hasMessageContaining("auth_time");
    assertThat(decision.challenge()).isEmpty();
  }

  @Test
  void an_api_key_is_forbidden() {
    RecentSignInDecision decision =
        decide(new WhoAuthenticationToken(principal(), UUID.randomUUID(), SignInMethod.API_KEY));

    assertThat(decision.refusal())
        .isInstanceOf(RecentSignInUnavailableException.class)
        .hasMessageContaining("API key");
  }

  @Test
  void a_permission_named_like_a_factor_does_not_count() {
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("FACTOR_OTT"));

    RecentSignInDecision decision =
        decide(new WhoAuthenticationToken(principal, UUID.randomUUID(), SignInMethod.API_KEY));

    assertThat(decision.isGranted()).isFalse();
  }

  @Test
  void someone_who_did_not_sign_in_through_who_is_forbidden() {
    RecentSignInDecision decision =
        decide(new TestingAuthenticationToken("someone", null, "FACTOR_OTT"));

    assertThat(decision.refusal()).isInstanceOf(RecentSignInUnavailableException.class);
  }

  @Test
  void a_sign_in_exactly_one_window_ago_is_too_old() {
    assertThat(decide(session(NOW.minus(WINDOW))).isGranted()).isFalse();
    assertThat(decide(session(NOW.minus(WINDOW).plusMillis(1))).isGranted()).isTrue();
  }

  @Test
  void no_authentication_is_not_signed_in() {
    assertThat(decide(null).refusal()).isInstanceOf(NotSignedInException.class);
    assertThat(
            decide(
                    new AnonymousAuthenticationToken(
                        "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))
                .refusal())
        .isInstanceOf(NotSignedInException.class);
  }

  @Test
  void with_window_changes_the_window() {
    RecentSignInAuthorizationManager shorter = manager.withWindow(Duration.ofMinutes(1));

    assertThat(shorter.window()).isEqualTo(Duration.ofMinutes(1));
    assertThat(shorter.authorize(() -> session(NOW.minusSeconds(120)), null).isGranted()).isFalse();
    assertThat(manager.authorize(() -> session(NOW.minusSeconds(120)), null).isGranted()).isTrue();
  }

  @Test
  void the_window_must_be_positive() {
    assertThatIllegalArgumentException().isThrownBy(() -> manager.withWindow(Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> manager.withWindow(Duration.ofSeconds(-1)));
  }

  @Test
  void a_denied_method_call_throws_the_refusal_and_sets_the_challenge() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest(), response));
    RecentSignInDecision decision =
        decide(signedIn(SignInMethod.JWT, NOW.minus(WINDOW).minusSeconds(1)));

    assertThatThrownBy(
            () -> decision.handleDeniedInvocation(mock(MethodInvocation.class), decision))
        .isSameAs(decision.refusal());
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(STEP_UP);
  }

  @Test
  void a_stale_jwt_outside_a_request_still_throws() {
    RecentSignInDecision decision =
        decide(signedIn(SignInMethod.JWT, NOW.minus(WINDOW).minusSeconds(1)));

    assertThatThrownBy(
            () -> decision.handleDeniedInvocation(mock(MethodInvocation.class), decision))
        .isSameAs(decision.refusal());
  }

  @Test
  void a_stale_jwt_with_no_response_to_write_still_throws() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    RecentSignInDecision decision =
        decide(signedIn(SignInMethod.JWT, NOW.minus(WINDOW).minusSeconds(1)));

    assertThatThrownBy(
            () -> decision.handleDeniedInvocation(mock(MethodInvocation.class), decision))
        .isSameAs(decision.refusal());
  }

  @Test
  void a_committed_response_gets_no_challenge() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setCommitted(true);
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest(), response));
    RecentSignInDecision decision =
        decide(signedIn(SignInMethod.JWT, NOW.minus(WINDOW).minusSeconds(1)));

    assertThatThrownBy(
            () -> decision.handleDeniedInvocation(mock(MethodInvocation.class), decision))
        .isSameAs(decision.refusal());
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
  }

  @Test
  void a_stale_session_in_a_request_sets_no_challenge() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest(), response));
    RecentSignInDecision decision = decide(session(NOW.minus(WINDOW).minusSeconds(1)));

    assertThatThrownBy(
            () -> decision.handleDeniedInvocation(mock(MethodInvocation.class), decision))
        .isInstanceOf(ReauthenticationRequiredException.class);
    assertThat(response.getHeader("WWW-Authenticate")).isNull();
  }

  @Test
  void an_unauthenticated_caller_is_not_signed_in() {
    assertThat(
            decide(UsernamePasswordAuthenticationToken.unauthenticated("someone", "secret"))
                .refusal())
        .isInstanceOf(NotSignedInException.class);
  }

  @Test
  void a_denied_method_call_outside_a_request_still_throws() {
    RecentSignInDecision decision = decide(session(NOW.minus(WINDOW).minusSeconds(1)));

    assertThatThrownBy(
            () -> decision.handleDeniedInvocation(mock(MethodInvocation.class), decision))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void a_factor_who_does_not_stamp_does_not_count() {
    WhoAuthenticationToken token =
        new WhoAuthenticationToken(
            principal(),
            UUID.randomUUID(),
            null,
            FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(NOW)
                .build());

    assertThat(decide(token).refusal()).isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void the_same_window_is_the_same_manager() {
    assertThat(manager.withWindow(WINDOW)).isSameAs(manager);
  }

  @Test
  void a_decision_says_whether_it_was_granted() {
    assertThat(decide(session(NOW))).hasToString("RecentSignInDecision[granted=true]");
    assertThat(decide(null)).hasToString("RecentSignInDecision[granted=false]");
  }
}
