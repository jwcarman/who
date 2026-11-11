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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

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

  private void assertNoBody() throws Exception {
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(response.getContentType()).isNull();
  }
}
