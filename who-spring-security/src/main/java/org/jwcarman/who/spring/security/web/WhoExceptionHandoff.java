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
package org.jwcarman.who.spring.security.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Hands an exception raised outside Spring MVC (in a security filter, failure handler or entry
 * point) to the application's exception resolvers, so the application's own
 * {@code @RestControllerAdvice} answers it like any controller exception. who's autoconfiguration
 * adapts Spring MVC's {@code handlerExceptionResolver} bean to a {@link Resolver}. who writes no
 * error body: when nothing resolves the exception, it calls {@code response.sendError} with the
 * exception's {@link ResponseStatus} ({@code 409} for a {@link LastCredentialException}), or the
 * fallback, and lets the application's error handling answer. An exception sent as a {@code 5xx}
 * that way is logged at {@code ERROR} with its stack trace first, since nothing else saw it.
 */
public final class WhoExceptionHandoff {

  /**
   * The response header naming a failure that carries a {@link WhoErrorCode}, or a {@link
   * LastCredentialException} ({@value LastCredentialException#ERROR_CODE}).
   */
  public static final String HEADER = "Who-Error";

  private static final Logger log = LoggerFactory.getLogger(WhoExceptionHandoff.class);

  /** Resolves an exception the way the application resolves its controllers' exceptions. */
  @FunctionalInterface
  public interface Resolver {

    /**
     * Answers {@code exception} on {@code response}.
     *
     * @param request the request
     * @param response the response
     * @param exception what went wrong
     * @return whether the exception was handled
     */
    boolean resolve(HttpServletRequest request, HttpServletResponse response, Exception exception);
  }

  private final @Nullable Resolver resolver;

  /**
   * Creates the handoff.
   *
   * @param resolver the application's resolver; null to always fall back to {@code sendError}
   */
  public WhoExceptionHandoff(@Nullable Resolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Hands {@code exception} to the application. A response already committed is left alone. When
   * nothing resolves it and its status is a {@code 5xx}, it is logged at {@code ERROR} with its
   * stack trace before {@code sendError}.
   *
   * @param request the request
   * @param response the response
   * @param exception what went wrong
   * @param fallback the status to send when the exception carries no {@link ResponseStatus}
   * @throws IOException if sending the error fails
   */
  public void handOff(
      HttpServletRequest request,
      HttpServletResponse response,
      Exception exception,
      HttpStatus fallback)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    // set first, so it stands beside whatever the application writes
    String code = codeOf(exception);
    if (code != null) {
      response.setHeader(HEADER, code);
    }
    // a resolver that answered and then failed has still answered
    if (resolver != null && resolved(request, response, exception) || response.isCommitted()) {
      return;
    }
    HttpStatus status = statusOf(exception, fallback);
    if (status.is5xxServerError()) {
      // nothing else will: the application's handlers never saw it, and sendError logs nothing
      log.error(
          "{} {} failed and no exception handler of the application answered it; sending {}",
          request.getMethod(),
          request.getRequestURI(),
          status.value(),
          exception);
    }
    response.sendError(status.value());
  }

  private boolean resolved(
      HttpServletRequest request, HttpServletResponse response, Exception exception) {
    try {
      return resolver.resolve(request, response, exception);
    } catch (RuntimeException e) {
      log.debug("Resolving {} failed: {}", exception.getClass().getName(), e.getClass().getName());
      return false;
    }
  }

  private static @Nullable String codeOf(Exception exception) {
    if (exception instanceof WhoErrorCode coded) {
      return coded.errorCode();
    }
    // who-core has no WhoErrorCode to implement
    return exception instanceof LastCredentialException last ? last.errorCode() : null;
  }

  private static HttpStatus statusOf(Exception exception, HttpStatus fallback) {
    // who-core has no Spring web to annotate it with
    if (exception instanceof LastCredentialException) {
      return HttpStatus.CONFLICT;
    }
    ResponseStatus status =
        AnnotatedElementUtils.findMergedAnnotation(exception.getClass(), ResponseStatus.class);
    return status == null ? fallback : status.code();
  }
}
