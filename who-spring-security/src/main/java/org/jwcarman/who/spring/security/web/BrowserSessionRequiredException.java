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
 * A signed-in request to an endpoint that needs a browser session on its own, made by something
 * else: a bearer token or an API key, a session cookie with an API key or a bearer token beside it,
 * or a sign-in no browser session loaded. who answers it {@code 403} with the code {@code
 * browser-session-required}. A client that sends both a session cookie and a credential header
 * should send only one.
 *
 * <p>It is an {@link AccessDeniedException}, so an application's handler for that still answers it.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class BrowserSessionRequiredException extends AccessDeniedException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what needs the browser session, such as {@code "Only a browser session can
   *     manage sessions"}
   */
  public BrowserSessionRequiredException(String message) {
    super(message);
  }

  /** {@return {@code browser-session-required}} */
  @Override
  public String errorCode() {
    return "browser-session-required";
  }
}
