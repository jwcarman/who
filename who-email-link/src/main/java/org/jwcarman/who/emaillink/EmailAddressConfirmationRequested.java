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
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import org.jwcarman.who.core.event.WhoEvent;

/**
 * A signed-in identity asked at {@code POST /who/me/email-addresses} to add an email address, or to
 * make one theirs in place of another, published once per request with what became of it. Published
 * on the email thread, after the caller has been answered.
 *
 * <p>The address is personal data.
 *
 * @param occurredAt when the request was handled
 * @param identityId the identity that asked
 * @param address the requested address, normalized, or {@code null} if the request carried no
 *     usable address
 * @param change whether the address was to replace one of the identity's addresses
 * @param outcome what became of the request
 */
public record EmailAddressConfirmationRequested(
    Instant occurredAt,
    UUID identityId,
    String address,
    boolean change,
    EmailAddressRequestOutcome outcome)
    implements WhoEvent {

  /** Validates that the required components are not null. */
  public EmailAddressConfirmationRequested {
    requireNonNull(occurredAt, "occurredAt must not be null");
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(outcome, "outcome must not be null");
  }
}
