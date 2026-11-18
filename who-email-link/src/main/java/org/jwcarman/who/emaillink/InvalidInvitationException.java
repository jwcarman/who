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

import org.jwcarman.who.core.exception.WhoException;
import org.jwcarman.who.spring.security.web.WhoErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An invitation who refuses as asked, before anything is created or with everything rolled back:
 * the address is missing or is not an address, a role name is blank or names no role, or roles were
 * asked for in an application without {@code who-rbac}. The message names the rule broken, never
 * the address.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidInvitationException extends WhoException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the rule the invitation breaks
   */
  public InvalidInvitationException(String message) {
    super("%s", message);
  }

  /** {@return {@code invalid-request}} */
  @Override
  public String errorCode() {
    return "invalid-request";
  }
}
