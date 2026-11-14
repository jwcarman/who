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
package org.jwcarman.who.oidc;

import java.io.Serial;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * There is no pending sign-up this browser may use: none, expired, or created for another identity.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PendingSignUpNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public PendingSignUpNotFoundException() {
    super("No pending sign-up");
  }
}
