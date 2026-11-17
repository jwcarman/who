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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoExceptionHandoffTest {

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final List<Exception> resolved = new ArrayList<>();

  private WhoExceptionHandoff.Resolver resolving(int status) {
    return (req, res, ex) -> {
      resolved.add(ex);
      res.setStatus(status);
      return true;
    };
  }

  private WhoExceptionHandoff.Resolver unresolving() {
    return (req, res, ex) -> {
      resolved.add(ex);
      return false;
    };
  }

  @Test
  void aResolvedExceptionIsLeftToTheResolver() throws Exception {
    NotSignedInException exception = new NotSignedInException();
    new WhoExceptionHandoff(resolving(418))
        .handOff(request, response, exception, HttpStatus.UNAUTHORIZED);
    assertThat(resolved).containsExactly(exception);
    assertThat(response.getStatus()).isEqualTo(418);
    assertThat(response.getErrorMessage()).isNull();
    assertNoBody();
  }

  @Test
  void anUnresolvedExceptionSendsItsAnnotatedStatus() throws Exception {
    new WhoExceptionHandoff(unresolving())
        .handOff(request, response, new ReauthenticationRequiredException(), HttpStatus.FORBIDDEN);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.isCommitted()).isTrue();
    assertNoBody();
  }

  @Test
  void the_last_credential_exception_is_a_conflict_whatever_the_fallback() throws Exception {
    new WhoExceptionHandoff(null)
        .handOff(request, response, new LastCredentialException(), HttpStatus.BAD_REQUEST);
    assertThat(response.getStatus()).isEqualTo(409);
    assertNoBody();
  }

  @Test
  void the_last_credential_exception_names_itself_in_the_header() throws Exception {
    new WhoExceptionHandoff(resolving(409))
        .handOff(request, response, new LastCredentialException(), HttpStatus.CONFLICT);
    assertThat(response.getHeader("Who-Error")).isEqualTo("last-credential");
  }

  @Test
  void anUnannotatedExceptionSendsTheFallback() throws Exception {
    new WhoExceptionHandoff(unresolving())
        .handOff(request, response, new AccessDeniedException("x"), HttpStatus.FORBIDDEN);
    assertThat(response.getStatus()).isEqualTo(403);
    assertNoBody();
  }

  @Test
  void withoutAResolverItSendsTheStatus() throws Exception {
    new WhoExceptionHandoff(null)
        .handOff(request, response, new NotSignedInException(), HttpStatus.FORBIDDEN);
    assertThat(response.getStatus()).isEqualTo(401);
    assertNoBody();
  }

  @Test
  void handOffLeavesCommittedResponsesAlone() throws Exception {
    response.setStatus(200);
    response.setCommitted(true);
    new WhoExceptionHandoff(resolving(418))
        .handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
    assertThat(resolved).isEmpty();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void aResolverThatThrowsFallsBackToSendError() throws Exception {
    WhoExceptionHandoff.Resolver throwing =
        (req, res, ex) -> {
          throw new IllegalStateException("broken advice");
        };
    new WhoExceptionHandoff(throwing)
        .handOff(
            request, response, new InvalidPasskeyRegistrationException(), HttpStatus.UNAUTHORIZED);
    assertThat(response.getStatus()).isEqualTo(400);
    assertNoBody();
  }

  @Test
  void aResolverThatCommitsAndThenThrowsIsLeftAlone() throws Exception {
    WhoExceptionHandoff.Resolver commitsThenThrows =
        (req, res, ex) -> {
          res.setStatus(401);
          ((MockHttpServletResponse) res).setCommitted(true);
          throw new IllegalStateException("failed after answering");
        };
    new WhoExceptionHandoff(commitsThenThrows)
        .handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getErrorMessage()).isNull();
  }

  @Test
  void a_coded_exception_names_itself_in_the_header_when_resolved() throws Exception {
    new WhoExceptionHandoff(resolving(401))
        .handOff(
            request, response, new ReauthenticationRequiredException(), HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeader("Who-Error")).isEqualTo("reauthentication-required");
  }

  @Test
  void a_coded_exception_names_itself_in_the_header_on_the_fallback() throws Exception {
    new WhoExceptionHandoff(null)
        .handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeader("Who-Error")).isEqualTo("not-signed-in");
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void an_uncoded_exception_sets_no_header() throws Exception {
    new WhoExceptionHandoff(null)
        .handOff(request, response, new AccessDeniedException("no"), HttpStatus.FORBIDDEN);
    assertThat(response.getHeader("Who-Error")).isNull();
  }

  @Test
  void a_committed_response_gets_no_header() throws Exception {
    response.setCommitted(true);
    new WhoExceptionHandoff(null)
        .handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeader("Who-Error")).isNull();
  }

  private void assertNoBody() throws Exception {
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(response.getContentType()).isNull();
  }

  @Nested
  @ExtendWith(OutputCaptureExtension.class)
  class Logging {

    @Test
    void an_unresolved_failure_answered_500_is_logged_at_error_with_its_stack_trace(
        CapturedOutput output) throws Exception {
      new WhoExceptionHandoff(unresolving())
          .handOff(
              request,
              response,
              new IllegalStateException("listener exploded"),
              HttpStatus.INTERNAL_SERVER_ERROR);

      assertThat(response.getStatus()).isEqualTo(500);
      assertThat(output)
          .contains("ERROR")
          .contains("java.lang.IllegalStateException: listener exploded")
          .contains("at org.jwcarman.who.spring.security.web.WhoExceptionHandoffTest");
    }

    @Test
    void a_failure_without_a_resolver_answered_500_is_logged_too(CapturedOutput output)
        throws Exception {
      new WhoExceptionHandoff(null)
          .handOff(
              request,
              response,
              new IllegalStateException("no resolver here"),
              HttpStatus.INTERNAL_SERVER_ERROR);

      assertThat(output).contains("ERROR").contains("no resolver here");
    }

    @Test
    void a_resolved_failure_is_left_to_the_application_to_log(CapturedOutput output)
        throws Exception {
      new WhoExceptionHandoff(resolving(500))
          .handOff(
              request,
              response,
              new IllegalStateException("the app answered"),
              HttpStatus.INTERNAL_SERVER_ERROR);

      assertThat(output).doesNotContain("the app answered");
    }

    @Test
    void an_unresolved_refusal_below_500_is_not_logged(CapturedOutput output) throws Exception {
      new WhoExceptionHandoff(unresolving())
          .handOff(
              request,
              response,
              new AccessDeniedException("refused quietly"),
              HttpStatus.FORBIDDEN);

      assertThat(output).doesNotContain("refused quietly");
    }
  }
}
