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
 * A sign-in attempt failed.
 *
 * @param occurredAt when it happened
 * @param method the sign-in mechanism
 * @param reason why it failed
 * @param address the email address involved, or {@code null} if not applicable
 * @param identityId the identity involved, or {@code null} if unknown
 * @param credentialId the credential involved, or {@code null} if unknown
 */
public record SignInFailed(
    Instant occurredAt,
    SignInMethod method,
    SignInFailureReason reason,
    String address,
    UUID identityId,
    UUID credentialId)
    implements WhoEvent {

  /** Validates that the required components are not null. */
  public SignInFailed {
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
  }
}
