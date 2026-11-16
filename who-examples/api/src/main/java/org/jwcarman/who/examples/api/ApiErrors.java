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
package org.jwcarman.who.examples.api;

import org.jwcarman.who.spring.security.web.InsufficientUserAuthenticationException;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * who hands its errors to the application's exception handling (see the Errors guide); every answer
 * here is a {@link ProblemDetail}. A token Spring rejects (expired, wrong audience, bad signature)
 * never gets here: Spring answers it with {@code 401} and {@code WWW-Authenticate: Bearer
 * error="invalid_token"}.
 */
@RestControllerAdvice
class ApiErrors {

  /** No token and no key: {@code 401}; who has already named the {@code Bearer} challenge. */
  @ExceptionHandler(NotSignedInException.class)
  ResponseEntity<ProblemDetail> notSignedIn() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Send an access token or an API key"));
  }

  /**
   * The token's person signed in too long ago to make a key: {@code 401}; who has already set the
   * step-up challenge, with {@code max_age}, in {@code WWW-Authenticate}.
   */
  @ExceptionHandler(InsufficientUserAuthenticationException.class)
  ResponseEntity<ProblemDetail> signInAgain() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Sign in again to make a key"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> denied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission"));
  }
}
