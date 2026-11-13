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
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A request needs a signed-in caller and has none. It carries no cause: Spring's {@code
 * InsufficientAuthenticationException} holds an {@code AccessDeniedException}, which an advice for
 * that type would answer with {@code 403} if it were in the cause chain.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class NotSignedInException extends AuthenticationException {

  @Serial private static final long serialVersionUID = 1L;

  static final String MESSAGE = "Not signed in";

  /** Creates the exception. */
  public NotSignedInException() {
    super(MESSAGE);
  }
}
