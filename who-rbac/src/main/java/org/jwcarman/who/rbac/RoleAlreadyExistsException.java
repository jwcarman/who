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
package org.jwcarman.who.rbac;

/**
 * Thrown when a role is created with a name that is already taken. It extends {@link
 * IllegalArgumentException}, so callers that already catch that type keep working. The message does
 * not name the role.
 */
public class RoleAlreadyExistsException extends IllegalArgumentException {

  /** Creates a new exception. */
  public RoleAlreadyExistsException() {
    super("A role with that name already exists");
  }

  /**
   * Creates a new exception that keeps the underlying failure.
   *
   * @param cause the constraint violation that revealed the duplicate
   */
  public RoleAlreadyExistsException(Throwable cause) {
    super("A role with that name already exists", cause);
  }
}
