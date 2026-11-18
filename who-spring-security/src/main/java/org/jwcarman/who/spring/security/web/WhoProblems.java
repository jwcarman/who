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
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;

import tools.jackson.databind.json.JsonMapper;

/**
 * Builds and writes {@link WhoProblem}s, who's answer to every error under its base path. Every
 * error gets a code: a who exception its own, anything else one named from its status.
 */
public final class WhoProblems {

  /** The prefix of every problem's {@code type}. */
  public static final String TYPE_PREFIX = "urn:who:error:";

  private static final String INVALID_CSRF_TOKEN = "invalid-csrf-token";

  private static final Logger log = LoggerFactory.getLogger(WhoProblems.class);

  // who's own mapper: the record pins its names, and the application's settings must not move them
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private WhoProblems() {}

  /**
   * The problem answering {@code exception}.
   *
   * @param exception what went wrong
   * @param fallback the status when nothing about the exception names one
   * @return the problem
   */
  public static WhoProblem of(Exception exception, HttpStatus fallback) {
    HttpStatus status = statusOf(exception, fallback);
    String code = codeOf(exception);
    if (code == null) {
      code = codeFor(status);
    }
    return new WhoProblem(
        TYPE_PREFIX + code,
        status.getReasonPhrase(),
        status.value(),
        detailOf(exception, status),
        code);
  }

  /**
   * Answers {@code exception} with its problem: the status, the {@code Who-Error} header and the
   * body. A {@code 5xx} is logged at {@code ERROR} with its stack trace first. A committed response
   * is left alone.
   *
   * @param request the request
   * @param response the response
   * @param exception what went wrong
   * @param fallback the status when nothing about the exception names one
   * @throws IOException if writing fails
   */
  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      Exception exception,
      HttpStatus fallback)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    WhoProblem problem = of(exception, fallback);
    if (problem.status() >= 500) {
      log.error(
          "{} {} failed; answering {}",
          LogValues.printable(request.getMethod()),
          LogValues.printable(request.getRequestURI()),
          problem.status(),
          exception);
    }
    response.resetBuffer();
    response.setStatus(problem.status());
    if (exception instanceof ErrorResponse error) {
      // headers the status calls for, such as a 405's Allow
      error
          .getHeaders()
          .forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
    }
    response.setHeader(WhoExceptionHandoff.HEADER, problem.code());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    byte[] body = JSON.writeValueAsBytes(problem);
    try {
      response.getOutputStream().write(body);
    } catch (IllegalStateException _) {
      // something before the failure wrote through the writer; resetBuffer keeps that choice
      response.setCharacterEncoding(StandardCharsets.UTF_8);
      response.getWriter().write(new String(body, StandardCharsets.UTF_8));
    }
  }

  /**
   * who's code for {@code exception}, if it has one.
   *
   * @param exception what went wrong
   * @return its code, or null for an exception that is not who's
   */
  static @Nullable String codeOf(Exception exception) {
    if (exception instanceof WhoErrorCode coded) {
      return coded.errorCode();
    }
    if (exception instanceof CsrfException) {
      // the client fetches a fresh token and tries again
      return INVALID_CSRF_TOKEN;
    }
    // who-core has no WhoErrorCode to implement
    return exception instanceof LastCredentialException last ? last.errorCode() : null;
  }

  static HttpStatus statusOf(Exception exception, HttpStatus fallback) {
    // who-core has no Spring web to annotate it with
    if (exception instanceof LastCredentialException) {
      return HttpStatus.CONFLICT;
    }
    ResponseStatus annotated =
        AnnotatedElementUtils.findMergedAnnotation(exception.getClass(), ResponseStatus.class);
    if (annotated != null) {
      return annotated.code();
    }
    if (exception instanceof ErrorResponse error) {
      HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
      return status == null ? fallback : status;
    }
    if (exception instanceof AuthenticationException) {
      return HttpStatus.UNAUTHORIZED;
    }
    if (exception instanceof AccessDeniedException) {
      return HttpStatus.FORBIDDEN;
    }
    return fallback;
  }

  private static String codeFor(HttpStatus status) {
    if (status.is5xxServerError()) {
      return "server-error";
    }
    return switch (status) {
      case BAD_REQUEST -> "invalid-request";
      case UNAUTHORIZED -> "unauthorized";
      case FORBIDDEN -> "forbidden";
      case NOT_FOUND -> "not-found";
      case METHOD_NOT_ALLOWED -> "method-not-allowed";
      case NOT_ACCEPTABLE -> "not-acceptable";
      case UNSUPPORTED_MEDIA_TYPE -> "unsupported-media-type";
      default -> "error";
    };
  }

  private static @Nullable String detailOf(Exception exception, HttpStatus status) {
    if (status.is5xxServerError()) {
      // a server error's message may carry what a client must not see
      return null;
    }
    if (exception instanceof ErrorResponse error) {
      return error.getBody().getDetail();
    }
    return exception.getMessage();
  }
}
