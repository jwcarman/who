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
package org.jwcarman.who.apikey;

import java.io.Serial;

import org.jwcarman.who.spring.security.web.WhoErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A request to make or rotate an API key that who refuses as it stands: a body that is not the
 * expected JSON, a lifetime or overlap that is not an ISO-8601 duration or is out of range, a blank
 * name, or a scope the owner does not hold. The message names the rule broken; it never holds a
 * key.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidApiKeyRequestException extends RuntimeException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the rule the request breaks
   */
  public InvalidApiKeyRequestException(String message) {
    super(message);
  }

  /** {@return {@code invalid-api-key-request}} */
  @Override
  public String errorCode() {
    return "invalid-api-key-request";
  }
}
