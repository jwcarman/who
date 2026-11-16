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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler;
import org.springframework.security.authorization.method.ThrowingMethodAuthorizationDeniedHandler;
import org.springframework.security.core.Authentication;

/**
 * Enforces {@link RequiresRecentSignIn @RequiresRecentSignIn} on methods and types, right after
 * Spring Security's {@code @PreAuthorize} checks, so a missing permission answers before a stale
 * sign-in does.
 */
public final class RecentSignInMethodInterceptor {

  private RecentSignInMethodInterceptor() {}

  /**
   * Creates the interceptor.
   *
   * @param recentSignIn the manager with the default window; asked for on first use
   * @return the interceptor, to register as an infrastructure bean
   */
  public static AuthorizationManagerBeforeMethodInterceptor create(
      Supplier<RecentSignInAuthorizationManager> recentSignIn) {
    Pointcut pointcut =
        new ComposablePointcut(new AnnotationMatchingPointcut(RequiresRecentSignIn.class, true))
            .union(new AnnotationMatchingPointcut(null, RequiresRecentSignIn.class, true));
    AuthorizationManagerBeforeMethodInterceptor interceptor =
        new AuthorizationManagerBeforeMethodInterceptor(
            pointcut, new ByAnnotation(requireNonNull(recentSignIn, "recentSignIn")));
    interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
    return interceptor;
  }

  /** Reads each method's window from its annotation, once. */
  private static final class ByAnnotation
      implements AuthorizationManager<MethodInvocation>, MethodAuthorizationDeniedHandler {

    private static final MethodAuthorizationDeniedHandler THROWING =
        new ThrowingMethodAuthorizationDeniedHandler();

    private final Supplier<RecentSignInAuthorizationManager> recentSignIn;
    private final Map<Target, Duration> windows = new ConcurrentHashMap<>();

    private ByAnnotation(Supplier<RecentSignInAuthorizationManager> recentSignIn) {
      this.recentSignIn = recentSignIn;
    }

    @Override
    public RecentSignInDecision authorize(
        Supplier<? extends @Nullable Authentication> authentication, MethodInvocation invocation) {
      RecentSignInAuthorizationManager manager = recentSignIn.get();
      Target target = Target.of(invocation);
      Duration window = windows.computeIfAbsent(target, t -> windowOf(t, manager));
      return manager.withWindow(window).authorize(authentication, invocation);
    }

    /**
     * Answers a stale sign-in with the decision's own refusal, not Spring Security's 403. Spring
     * also calls this with a denial thrown from inside the method, such as a service's own
     * {@code @RequiresPermission}; that denial is rethrown as it is.
     */
    @Override
    public @Nullable Object handleDeniedInvocation(
        MethodInvocation invocation, AuthorizationResult result) {
      return result instanceof RecentSignInDecision decision
          ? decision.handleDeniedInvocation(invocation, result)
          : THROWING.handleDeniedInvocation(invocation, result);
    }

    private static Duration windowOf(Target target, RecentSignInAuthorizationManager manager) {
      RequiresRecentSignIn annotation = target.find();
      if (annotation == null || annotation.within().isBlank()) {
        return manager.window();
      }
      try {
        return DurationFormatterUtils.detectAndParse(annotation.within().strip());
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            "@RequiresRecentSignIn(within = \""
                + annotation.within()
                + "\") on "
                + target.method()
                + " is not a duration",
            e);
      }
    }
  }

  /** A method called on a class, which together decide which annotation applies. */
  private record Target(Method method, Class<?> targetClass) {

    static Target of(MethodInvocation invocation) {
      Method method = invocation.getMethod();
      Object target = invocation.getThis();
      return new Target(
          method, target == null ? method.getDeclaringClass() : AopUtils.getTargetClass(target));
    }

    /**
     * The annotation on the method, else on the target's class or one of its supertypes, which
     * include the type declaring the method.
     */
    @Nullable RequiresRecentSignIn find() {
      Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
      RequiresRecentSignIn found =
          AnnotatedElementUtils.findMergedAnnotation(specific, RequiresRecentSignIn.class);
      return found != null
          ? found
          : AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresRecentSignIn.class);
    }
  }
}
