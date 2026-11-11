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

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A sign-in attempt failed: a bad, expired or used token, an unknown or invalid passkey, or an
 * inactive identity. The message is always {@value #MESSAGE}, so the reason never reaches a
 * response; the cause keeps it for logging.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class SignInFailedException extends AuthenticationException {

  static final String MESSAGE = "Sign-in failed";

  /**
   * Creates the exception.
   *
   * @param cause why the sign-in failed
   */
  public SignInFailedException(Throwable cause) {
    super(MESSAGE, cause);
  }
}
