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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Hands an exception raised outside Spring MVC (in a security filter, failure handler or entry
 * point) to the application's {@link HandlerExceptionResolver}, so the application's own
 * {@code @RestControllerAdvice} answers it like any controller exception. who writes no error body:
 * when nothing resolves the exception, it calls {@code response.sendError} with the exception's
 * {@link ResponseStatus}, or the fallback, and lets the application's error handling answer.
 */
public final class WhoExceptionHandoff {

  private static final Logger log = LoggerFactory.getLogger(WhoExceptionHandoff.class);

  private final @Nullable HandlerExceptionResolver resolver;

  /**
   * Creates the handoff.
   *
   * @param resolver the application's resolver, usually Spring MVC's {@code
   *     handlerExceptionResolver} bean; null to always fall back to {@code sendError}
   */
  public WhoExceptionHandoff(@Nullable HandlerExceptionResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Hands {@code exception} to the application. A response already committed is left alone.
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
    if (resolver != null && resolved(request, response, exception)) {
      return;
    }
    response.sendError(statusOf(exception, fallback).value());
  }

  private boolean resolved(
      HttpServletRequest request, HttpServletResponse response, Exception exception) {
    try {
      return resolver.resolveException(request, response, null, exception) != null;
    } catch (RuntimeException e) {
      log.debug("Resolving {} failed: {}", exception.getClass().getName(), e.getClass().getName());
      return false;
    }
  }

  private static HttpStatus statusOf(Exception exception, HttpStatus fallback) {
    ResponseStatus status =
        AnnotatedElementUtils.findMergedAnnotation(exception.getClass(), ResponseStatus.class);
    return status == null ? fallback : status.code();
  }
}
