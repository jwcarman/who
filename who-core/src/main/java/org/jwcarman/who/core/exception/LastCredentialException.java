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
package org.jwcarman.who.core.exception;

import java.io.Serial;

/**
 * Thrown when removing a credential would leave an identity with no way to sign in. The web layer
 * answers it with {@code 409} and names it {@value #ERROR_CODE} in its {@code Who-Error} header.
 */
public class LastCredentialException extends WhoException {

  /**
   * The code the web layer sends in its {@code Who-Error} header. who-core has no web layer, so it
   * cannot implement who's {@code WhoErrorCode}; the code is the same public contract.
   */
  public static final String ERROR_CODE = "last-credential";

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public LastCredentialException() {
    super("the identity's last sign-in credential cannot be removed");
  }

  /**
   * Returns the code the web layer sends in its {@code Who-Error} header.
   *
   * @return {@value #ERROR_CODE}
   */
  public String errorCode() {
    return ERROR_CODE;
  }
}
