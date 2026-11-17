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
 * A request to change an identity that holds a permission the caller does not: deactivating or
 * reactivating it, or assigning or removing one of its roles. who's admin endpoints change only
 * identities whose every permission, as who resolves them whatever their status, the caller holds
 * too; for an API key, those its scopes keep. An identity may always act on itself.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class IdentityOutranksCallerException extends RuntimeException implements WhoErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public IdentityOutranksCallerException() {
    super("Changing this identity needs every permission it holds");
  }

  /** {@return {@code identity-outranks-caller}} */
  @Override
  public String errorCode() {
    return "identity-outranks-caller";
  }
}
