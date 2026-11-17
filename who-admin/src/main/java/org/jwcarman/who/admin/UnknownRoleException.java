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
package org.jwcarman.who.admin;

import java.io.Serial;

import org.jwcarman.who.spring.security.web.WhoErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A request named a role that does not exist. who's admin endpoints assign and remove only roles
 * the application has made.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UnknownRoleException extends RuntimeException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public UnknownRoleException() {
    super("No such role");
  }

  /** {@return {@code unknown-role}} */
  @Override
  public String errorCode() {
    return "unknown-role";
  }
}
