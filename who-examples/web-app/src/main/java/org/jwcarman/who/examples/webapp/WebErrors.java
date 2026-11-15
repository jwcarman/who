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
package org.jwcarman.who.examples.webapp;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * who's default chain answers a signed-out request with {@code 401} and no redirect. A page asked
 * for by a browser should land on the sign-in page instead, so this sends it there; a request from
 * {@code who.js} keeps its {@code 401}. who hands errors raised in its filters to this advice too.
 */
@ControllerAdvice
class WebErrors {

  @ExceptionHandler(NotSignedInException.class)
  ResponseEntity<ProblemDetail> notSignedIn(HttpServletRequest request) {
    if (wantsPage(request)) {
      return ResponseEntity.status(HttpStatus.SEE_OTHER)
          .header(HttpHeaders.LOCATION, "/signin")
          .build();
    }
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in to continue"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> denied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission"));
  }

  private static boolean wantsPage(HttpServletRequest request) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
  }
}
