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
package org.jwcarman.who.examples.webandapi;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * who hands its errors to the application's exception handling, from both chains (see the Errors
 * guide). An API call gets a {@link ProblemDetail}; a signed-out browser asking for a page is sent
 * to the sign-in page instead. The decision is by path, because who hands over an error raised in
 * its filters without a controller, so an advice limited to some controllers would never see it.
 */
@ControllerAdvice
class Errors {

  @ExceptionHandler(NotSignedInException.class)
  ResponseEntity<ProblemDetail> notSignedIn(HttpServletRequest request) {
    if (!isApi(request) && wantsPage(request)) {
      return ResponseEntity.status(HttpStatus.SEE_OTHER)
          .header(HttpHeaders.LOCATION, "/signin")
          .build();
    }
    // on the API, who has already named the Bearer challenge
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                isApi(request) ? "Send an access token or an API key" : "Sign in to continue"));
  }

  /**
   * The person signed in too long ago for this action, such as making a key: a page goes to sign in
   * again and back; the API says so.
   */
  @ExceptionHandler(ReauthenticationRequiredException.class)
  ResponseEntity<ProblemDetail> signInAgain(HttpServletRequest request) {
    if (!isApi(request) && wantsPage(request)) {
      return ResponseEntity.status(HttpStatus.SEE_OTHER)
          .header(
              HttpHeaders.LOCATION,
              UriComponentsBuilder.fromPath(request.getContextPath() + "/signin")
                  .queryParam(
                      "again", request.getRequestURI().substring(request.getContextPath().length()))
                  .encode()
                  .build()
                  .toUriString())
          .build();
    }
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in again to do this"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> denied() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission"));
  }

  private static boolean isApi(HttpServletRequest request) {
    return request.getRequestURI().startsWith(request.getContextPath() + "/api/");
  }

  private static boolean wantsPage(HttpServletRequest request) {
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
  }
}
