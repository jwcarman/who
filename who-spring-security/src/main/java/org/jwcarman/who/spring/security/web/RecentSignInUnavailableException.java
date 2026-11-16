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

import java.io.Serial;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An action needs a recent sign-in, and the caller has no sign-in time that could ever be recent:
 * an API key, or a bearer token without an {@code auth_time} claim. Signing in again would not
 * help, so this is a {@code 403}, not a challenge.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RecentSignInUnavailableException extends AccessDeniedException
    implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  private RecentSignInUnavailableException(String message) {
    super(message);
  }

  /**
   * A bearer token that does not say when its person signed in.
   *
   * @return the exception
   */
  public static RecentSignInUnavailableException noSignInTime() {
    return new RecentSignInUnavailableException(
        "This token has no auth_time claim, so who cannot tell when you signed in. "
            + "Configure your identity provider to include it.");
  }

  /**
   * An API key, which stands for no sign-in by a person.
   *
   * @return the exception
   */
  public static RecentSignInUnavailableException apiKey() {
    return new RecentSignInUnavailableException(
        "An API key cannot make this request: it needs a recent sign-in by a person.");
  }

  /**
   * A caller who did not sign in through who, so who cannot tell when they did.
   *
   * @return the exception
   */
  public static RecentSignInUnavailableException unknownSignIn() {
    return new RecentSignInUnavailableException(
        "This request needs a recent sign-in, and who cannot tell when this caller signed in.");
  }

  /** {@return {@code recent-sign-in-unavailable}} */
  @Override
  public String errorCode() {
    return "recent-sign-in-unavailable";
  }
}
