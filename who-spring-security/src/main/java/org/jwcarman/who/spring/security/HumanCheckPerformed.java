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
package org.jwcarman.who.spring.security;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import org.jwcarman.who.core.event.WhoEvent;

/**
 * A request to a human-checked endpoint was checked, published once per such request with what
 * became of it, before the endpoint runs. A wave of {@code REFUSED} is a bot at the door; nothing
 * but {@code REFUSED} or {@code ERROR} is a broken widget or vendor.
 *
 * <p>The client address is personal data. The token is never published.
 *
 * @param occurredAt when the request was checked
 * @param check the check's {@linkplain WhoHumanCheck#name() name}
 * @param action the endpoint's action, such as {@code email-send}
 * @param clientIp the client's address, as the check was told it; {@code unknown} when the
 *     container reported none
 * @param outcome what became of the request
 */
public record HumanCheckPerformed(
    Instant occurredAt, String check, String action, String clientIp, HumanCheckOutcome outcome)
    implements WhoEvent {

  /** Validates that no component is null. */
  public HumanCheckPerformed {
    requireNonNull(occurredAt, "occurredAt must not be null");
    requireNonNull(check, "check must not be null");
    requireNonNull(action, "action must not be null");
    requireNonNull(clientIp, "clientIp must not be null");
    requireNonNull(outcome, "outcome must not be null");
  }
}
