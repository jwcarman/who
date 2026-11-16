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

import static java.util.Objects.requireNonNull;

import java.io.Serial;
import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A bearer token whose person signed in too long ago for this action: the client must have them
 * sign in again at the identity provider and come back with a new token. who sends the RFC 9470
 * step-up challenge with it, in the {@code WWW-Authenticate} header given by {@link #challenge()}.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InsufficientUserAuthenticationException extends AuthenticationException
    implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  private static final String DESCRIPTION = "A more recent sign-in is required";

  /** How recent the sign-in must be. */
  private final Duration maxAge;

  /**
   * Creates the exception.
   *
   * @param maxAge how recent the sign-in must be
   */
  public InsufficientUserAuthenticationException(Duration maxAge) {
    super(DESCRIPTION);
    this.maxAge = requireNonNull(maxAge, "maxAge must not be null");
  }

  /**
   * Returns how recent the sign-in must be: the {@code max_age} the client asks the identity
   * provider for.
   *
   * @return the window
   */
  public Duration maxAge() {
    return maxAge;
  }

  /**
   * Returns the RFC 9470 challenge, for the {@code WWW-Authenticate} header.
   *
   * @return the challenge
   */
  public String challenge() {
    return "Bearer error=\"insufficient_user_authentication\", error_description=\""
        + DESCRIPTION
        + "\", max_age="
        + maxAge.toSeconds();
  }

  /** {@return {@code insufficient-user-authentication}} */
  @Override
  public String errorCode() {
    return "insufficient-user-authentication";
  }
}
