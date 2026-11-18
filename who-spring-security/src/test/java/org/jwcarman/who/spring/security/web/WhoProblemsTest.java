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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoProblemsTest {

  private static final HttpStatus FALLBACK = HttpStatus.INTERNAL_SERVER_ERROR;

  @Test
  void a_who_exception_names_itself() {
    WhoProblem problem = WhoProblems.of(new SessionNotFoundException(), FALLBACK);

    assertThat(problem)
        .isEqualTo(
            new WhoProblem(
                "urn:who:error:session-not-found",
                "Not Found",
                404,
                "No such session",
                "session-not-found"));
  }

  @Test
  void the_last_credential_is_a_conflict() {
    WhoProblem problem = WhoProblems.of(new LastCredentialException(), FALLBACK);

    assertThat(problem.status()).isEqualTo(409);
    assertThat(problem.code()).isEqualTo("last-credential");
    assertThat(problem.type()).isEqualTo("urn:who:error:last-credential");
  }

  @Test
  void a_bad_request_from_spring_is_an_invalid_request() {
    WhoProblem status =
        WhoProblems.of(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ending every session needs it"),
            FALLBACK);
    WhoProblem parameter =
        WhoProblems.of(new MissingServletRequestParameterException("others", "String"), FALLBACK);

    assertThat(status.status()).isEqualTo(400);
    assertThat(status.code()).isEqualTo("invalid-request");
    assertThat(status.detail()).isEqualTo("Ending every session needs it");
    assertThat(parameter.status()).isEqualTo(400);
    assertThat(parameter.code()).isEqualTo("invalid-request");
  }

  @Test
  void a_not_found_from_spring_is_not_found() {
    WhoProblem problem =
        WhoProblems.of(new ResponseStatusException(HttpStatus.NOT_FOUND), FALLBACK);

    assertThat(problem.code()).isEqualTo("not-found");
    assertThat(problem.title()).isEqualTo("Not Found");
  }

  @Test
  void access_denied_is_forbidden() {
    WhoProblem problem = WhoProblems.of(new AccessDeniedException("no"), FALLBACK);

    assertThat(problem.status()).isEqualTo(403);
    assertThat(problem.code()).isEqualTo("forbidden");
  }

  @Test
  void a_refused_csrf_token_names_itself() {
    WhoProblem problem =
        WhoProblems.of(
            new InvalidCsrfTokenException(new DefaultCsrfToken("X", "_csrf", "a"), "b"), FALLBACK);

    assertThat(problem.status()).isEqualTo(403);
    assertThat(problem.code()).isEqualTo("invalid-csrf-token");
  }

  @Test
  void an_authentication_failure_without_a_code_is_unauthorized() {
    WhoProblem problem = WhoProblems.of(new BadCredentialsException("bad"), FALLBACK);

    assertThat(problem.status()).isEqualTo(401);
    assertThat(problem.code()).isEqualTo("unauthorized");
  }

  @Test
  void anything_else_is_a_server_error_that_says_nothing_of_itself() {
    WhoProblem problem =
        WhoProblems.of(new IllegalStateException("connection string with a password"), FALLBACK);

    assertThat(problem)
        .isEqualTo(
            new WhoProblem(
                "urn:who:error:server-error", "Internal Server Error", 500, null, "server-error"));
  }

  @Test
  void the_fallback_gives_an_uncoded_exception_its_status() {
    WhoProblem problem =
        WhoProblems.of(new IllegalArgumentException("odd"), HttpStatus.BAD_REQUEST);

    assertThat(problem.status()).isEqualTo(400);
    assertThat(problem.code()).isEqualTo("invalid-request");
  }

  @Test
  void a_method_not_allowed_keeps_its_allow_header() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    WhoProblems.write(
        new MockHttpServletRequest("PATCH", "/who/me/sessions"),
        response,
        new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "DELETE")),
        HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(response.getStatus()).isEqualTo(405);
    assertThat(response.getHeader("Who-Error")).isEqualTo("method-not-allowed");
    assertThat(response.getHeaderValues("Allow").toString()).contains("GET").contains("DELETE");
  }
}
