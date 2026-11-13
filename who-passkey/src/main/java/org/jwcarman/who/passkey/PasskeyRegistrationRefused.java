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
package org.jwcarman.who.passkey;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.core.event.WhoEvent;

/**
 * A signed-in identity tried to add a passkey, and was refused because it signed in longer ago than
 * the registration window allows. The client is told to sign in again.
 *
 * @param occurredAt when it happened
 * @param identityId the identity that tried to add the passkey
 * @param reason why the registration was refused
 */
public record PasskeyRegistrationRefused(
    Instant occurredAt, UUID identityId, PasskeyRegistrationRefusedReason reason)
    implements WhoEvent {

  /** Validates that no component is null. */
  public PasskeyRegistrationRefused {
    requireNonNull(occurredAt, "occurredAt must not be null");
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(reason, "reason must not be null");
  }
}
