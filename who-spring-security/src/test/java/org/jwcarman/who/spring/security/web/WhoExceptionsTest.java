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

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ResponseStatus;

class WhoExceptionsTest {

  @Test
  void signInFailedHidesItsCause() {
    BadCredentialsException cause = new BadCredentialsException("token expired");
    SignInFailedException exception = new SignInFailedException(cause);
    assertThat(exception).hasMessage("Sign-in failed");
    assertThat(exception.getCause()).isSameAs(cause);
  }

  @Test
  void eachExceptionCarriesItsStatus() {
    assertThat(status(SignInFailedException.class)).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(status(NotSignedInException.class)).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(status(ReauthenticationRequiredException.class)).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(status(InvalidPasskeyRegistrationException.class)).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void messagesAreFixed() {
    assertThat(new NotSignedInException()).hasMessage("Not signed in");
    assertThat(new ReauthenticationRequiredException()).hasMessage("Reauthentication required");
    assertThat(new InvalidPasskeyRegistrationException())
        .hasMessage("Invalid passkey registration");
  }

  @Test
  void notSignedInKeepsItsCause() {
    BadCredentialsException cause = new BadCredentialsException("x");
    assertThat(new NotSignedInException(cause).getCause()).isSameAs(cause);
    assertThat(new NotSignedInException(cause)).hasMessage("Not signed in");
  }

  @Test
  void invalidRegistrationHasNoCause() {
    assertThat(new InvalidPasskeyRegistrationException().getCause()).isNull();
  }

  private static HttpStatus status(Class<?> type) {
    return AnnotatedElementUtils.findMergedAnnotation(type, ResponseStatus.class).code();
  }
}
