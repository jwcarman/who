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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
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
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RecentSignInMethodInterceptorTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  static class Actions {
    @RequiresRecentSignIn(within = "soon")
    public void badWindow() {
      // the annotation is the test
    }

    @RequiresRecentSignIn(within = " 2m ")
    public void twoMinutes() {
      // the annotation is the test
    }

    @RequiresRecentSignIn
    public void usual() {
      // the annotation is the test
    }

    public void unmarked() {
      // no annotation: the interceptor is called on it directly
    }
  }

  @RequiresRecentSignIn(within = "1m")
  static class OneMinute {
    public void act() {
      // the type's annotation is the test
    }
  }

  private final AuthorizationManagerBeforeMethodInterceptor interceptor =
      RecentSignInMethodInterceptor.create(
          () ->
              new RecentSignInAuthorizationManager(
                  Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC)));

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static MethodInvocation call(String name, Object target) throws Exception {
    return call(Actions.class, name, target);
  }

  private static MethodInvocation call(Class<?> type, String name, Object target) throws Exception {
    Method method = type.getMethod(name);
    MethodInvocation invocation = mock(MethodInvocation.class);
    when(invocation.getMethod()).thenReturn(method);
    when(invocation.getThis()).thenReturn(target);
    return invocation;
  }

  private static void signedInAgo(Duration ago) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            WhoAuthenticationToken.signedIn(
                new WhoPrincipal(Identity.create(), Set.of()),
                UUID.randomUUID(),
                SignInMethod.PASSKEY,
                NOW.minus(ago)));
  }

  @Test
  void an_annotation_on_the_type_covers_its_methods() throws Exception {
    signedInAgo(Duration.ofMinutes(2));
    MethodInvocation invocation = call(OneMinute.class, "act", new OneMinute());

    assertThatThrownBy(() -> interceptor.invoke(invocation))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void without_a_window_of_its_own_the_configured_one_applies() throws Throwable {
    signedInAgo(Duration.ofMinutes(9));
    MethodInvocation recent = call("usual", new Actions());
    when(recent.proceed()).thenReturn("done");

    assertThat(interceptor.invoke(recent)).isEqualTo("done");

    signedInAgo(Duration.ofMinutes(11));
    MethodInvocation stale = call("usual", new Actions());

    assertThatThrownBy(() -> interceptor.invoke(stale))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void a_method_without_the_annotation_gets_the_configured_window() throws Exception {
    signedInAgo(Duration.ofMinutes(11));
    MethodInvocation invocation = call("unmarked", new Actions());

    assertThatThrownBy(() -> interceptor.invoke(invocation))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void a_denial_inside_the_method_stays_a_denial() throws Throwable {
    signedInAgo(Duration.ZERO);
    MethodInvocation invocation = call("usual", new Actions());
    AuthorizationDeniedException inner =
        new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));
    when(invocation.proceed()).thenThrow(inner);

    assertThatThrownBy(() -> interceptor.invoke(invocation)).isSameAs(inner);
  }

  @Test
  void it_runs_right_after_pre_authorize() {
    assertThat(interceptor.getOrder())
        .isEqualTo(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
  }

  @Test
  void a_window_that_is_not_a_duration_says_where_it_is() throws Exception {
    signedInAgo(Duration.ZERO);
    MethodInvocation invocation = call("badWindow", new Actions());

    assertThatIllegalStateException()
        .isThrownBy(() -> interceptor.invoke(invocation))
        .withMessageContaining("soon")
        .withMessageContaining("badWindow");
  }

  @Test
  void a_call_without_a_target_reads_the_declaring_type() throws Exception {
    signedInAgo(Duration.ofMinutes(3));
    MethodInvocation invocation = call("twoMinutes", null);

    assertThatThrownBy(() -> interceptor.invoke(invocation))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
