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
 * A request named an API key that is not one of the caller's own, or asked for the key in use by a
 * request that no API key signed in. An unknown key and another identity's key are the same answer.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ApiKeyNotFoundException extends RuntimeException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public ApiKeyNotFoundException() {
    super("No such API key");
  }

  /** {@return {@code api-key-not-found}} */
  @Override
  public String errorCode() {
    return "api-key-not-found";
  }
}
