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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A request an API key signed in, to an API-key endpoint other than {@code /current}: a key never
 * lists, makes, rotates or revokes keys, so a leaked key cannot make itself permanent. who answers
 * it {@code 403} with the code {@code api-key-cannot-manage-keys}; a browser session or a bearer
 * token may manage keys.
 *
 * <p>It is an {@link AccessDeniedException}, so an application's handler for that still answers it.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ApiKeyCannotManageKeysException extends AccessDeniedException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public ApiKeyCannotManageKeysException() {
    super("An API key cannot manage API keys");
  }

  /** {@return {@code api-key-cannot-manage-keys}} */
  @Override
  public String errorCode() {
    return "api-key-cannot-manage-keys";
  }
}
