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

/**
 * Credentials no longer linked to any identity were purged.
 *
 * @param occurredAt when it happened
 * @param count how many credentials were purged
 */
public record OrphanedCredentialsPurged(Instant occurredAt, int count) implements WhoEvent {

  /** Validates that the timestamp is not null. */
  public OrphanedCredentialsPurged {
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
  }
}
