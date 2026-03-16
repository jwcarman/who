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
package org.jwcarman.who.enrollment;

import java.util.UUID;

import org.jwcarman.who.core.exception.WhoException;

/** Thrown when an enrollment token has passed its expiration time. */
public class EnrollmentTokenExpiredException extends WhoException {

  /**
   * Creates a new exception for the given token id.
   *
   * @param tokenId the id of the expired token
   */
  public EnrollmentTokenExpiredException(UUID tokenId) {
    super("Enrollment token has expired: %s", tokenId);
  }
}
