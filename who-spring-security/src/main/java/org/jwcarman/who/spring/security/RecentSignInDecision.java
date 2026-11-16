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

import java.io.Serial;
import java.util.Optional;

import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.MethodAuthorizationDeniedHandler;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Whether a caller signed in recently enough, and, when not, how to answer: the exception to throw
 * and, for a bearer token, the step-up challenge to send.
 *
 * <p>A refused method call throws the answer itself, rather than Spring Security's {@code
 * AuthorizationDeniedException}, so that an application advice mapping every {@code
 * AccessDeniedException} to {@code 403} still answers a stale sign-in with {@code 401}. who's
 * security chains answer a refused URL rule the same way.
 */
public final class RecentSignInDecision extends AuthorizationDecision
    implements MethodAuthorizationDeniedHandler {

  @Serial private static final long serialVersionUID = 1L;

  private static final RecentSignInDecision GRANTED = new RecentSignInDecision(null, null);

  private final transient @Nullable RuntimeException refusal;
  private final @Nullable String challenge;

  private RecentSignInDecision(@Nullable RuntimeException refusal, @Nullable String challenge) {
    super(refusal == null);
    this.refusal = refusal;
    this.challenge = challenge;
  }

  static RecentSignInDecision granted() {
    return GRANTED;
  }

  static RecentSignInDecision refused(RuntimeException refusal, @Nullable String challenge) {
    return new RecentSignInDecision(requireNonNull(refusal, "refusal must not be null"), challenge);
  }

  /**
   * Returns the answer to a refused request: {@code ReauthenticationRequiredException}, {@code
   * InsufficientUserAuthenticationException}, {@code RecentSignInUnavailableException} or {@code
   * NotSignedInException}.
   *
   * @return the exception, or {@code null} when granted
   */
  public @Nullable RuntimeException refusal() {
    return refusal;
  }

  /**
   * Returns the {@code WWW-Authenticate} challenge to send with the refusal, for a bearer token
   * whose sign-in is too old.
   *
   * @return the challenge, or empty
   */
  public Optional<String> challenge() {
    return Optional.ofNullable(challenge);
  }

  /**
   * Sets the challenge on the current response, if there is one, and throws the refusal.
   *
   * @param invocation the refused call
   * @param result the decision
   * @return never
   */
  @Override
  public @Nullable Object handleDeniedInvocation(
      MethodInvocation invocation, AuthorizationResult result) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (challenge != null
        && attributes instanceof ServletRequestAttributes servlet
        && servlet.getResponse() != null
        && !servlet.getResponse().isCommitted()) {
      servlet.getResponse().setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
    }
    throw requireNonNull(refusal, "a granted decision is never denied");
  }

  @Override
  public String toString() {
    return "RecentSignInDecision[granted=" + isGranted() + "]";
  }
}
