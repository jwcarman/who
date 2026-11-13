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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.web.InvalidPasskeyRegistrationException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.SignInFailedException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every former who error reaches the application's own advice, from inside the filters too. */
@SpringBootTest(
    classes = {ErrorHandoffScenarios.App.class, ErrorHandoffTest.WithAdvice.class},
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=app.example.com"
    })
@Testcontainers
class ErrorHandoffTest extends ErrorHandoffScenarios {

  @Configuration(proxyBeanMethods = false)
  static class WithAdvice {

    @Bean
    TestAdvice testAdvice() {
      return new TestAdvice();
    }
  }

  /** Marks every answer it writes with the exception's simple name. */
  @RestControllerAdvice
  static class TestAdvice {

    @ExceptionHandler({
      SignInFailedException.class,
      NotSignedInException.class,
      ReauthenticationRequiredException.class
    })
    ProblemDetail unauthorized(Exception e) {
      return problem(HttpStatus.UNAUTHORIZED, e);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
      return problem(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(InvalidPasskeyRegistrationException.class)
    ProblemDetail invalid(InvalidPasskeyRegistrationException e) {
      return problem(HttpStatus.BAD_REQUEST, e);
    }

    private static ProblemDetail problem(HttpStatus status, Exception e) {
      ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "from the advice");
      problem.setProperty("exception", e.getClass().getSimpleName());
      return problem;
    }
  }

  private static void assertAdvised(MvcResult result, int status, String exception)
      throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
    assertThat(result.getResponse().getContentAsString())
        .contains("\"detail\":\"from the advice\"")
        .contains("\"exception\":\"" + exception + "\"");
  }

  @Test
  void signInFailureReachesTheAdviceAsSignInFailed() throws Exception {
    MvcResult result = badSignInToken();
    assertAdvised(result, 401, "SignInFailedException");
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("invalid_token")
        .doesNotContain("x".repeat(43));
  }

  @Test
  void passkeySignInFailureReachesTheAdvice() throws Exception {
    assertAdvised(unknownPasskeySignIn(), 401, "SignInFailedException");
  }

  @Test
  void signedOutRequestReachesTheAdvice() throws Exception {
    assertAdvised(signedOutGuarded(), 401, "NotSignedInException");
  }

  @Test
  void signedOutPermissionCheckIsNotSignedIn() throws Exception {
    assertThat(signedOutGuarded().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void whoMeSignedOutReachesTheAdvice() throws Exception {
    assertAdvised(signedOutWhoMe(), 401, "NotSignedInException");
  }

  @Test
  void missingPermissionReachesTheAdvice() throws Exception {
    // Spring Security 7 denies with its AccessDeniedException subclass
    assertAdvised(missingPermission(), 403, "AuthorizationDeniedException");
  }

  @Test
  void urlRuleDenialReachesTheAdvice() throws Exception {
    assertAdvised(deniedByUrlRule(), 403, "AuthorizationDeniedException");
  }

  @Test
  void passkeyRegistrationErrorsReachTheAdvice() throws Exception {
    assertAdvised(registrationSignedOut(), 401, "NotSignedInException");
    assertAdvised(registrationOutsideTheWindow(), 401, "ReauthenticationRequiredException");
    assertAdvised(invalidRegistration(), 400, "InvalidPasskeyRegistrationException");
  }
}
