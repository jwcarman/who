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
package org.jwcarman.who.core.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A sign-in succeeded.
 *
 * @param occurredAt when it happened
 * @param method the sign-in mechanism
 * @param identityId the identity that signed in
 * @param credentialId the credential that was used
 */
public record SignInSucceeded(
    Instant occurredAt, SignInMethod method, UUID identityId, UUID credentialId)
    implements WhoEvent {

  /** Validates that no component is null. */
  public SignInSucceeded {
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(identityId, "identityId must not be null");
    Objects.requireNonNull(credentialId, "credentialId must not be null");
  }
}
