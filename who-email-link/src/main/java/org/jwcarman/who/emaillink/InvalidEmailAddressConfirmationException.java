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
package org.jwcarman.who.emaillink;

import java.io.Serial;

import org.jwcarman.who.spring.security.web.WhoErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A confirmation at {@code POST /who/me/email-addresses/confirmations} matched nothing: no request
 * by this identity, an expired or used one, a wrong code, or a code with no attempts left. The
 * message never says which.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidEmailAddressConfirmationException extends RuntimeException
    implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidEmailAddressConfirmationException() {
    super("Email address confirmation failed");
  }

  /** {@return {@code invalid-confirmation}} */
  @Override
  public String errorCode() {
    return "invalid-confirmation";
  }
}
