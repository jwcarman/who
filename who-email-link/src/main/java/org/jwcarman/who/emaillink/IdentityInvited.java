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
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.event.WhoEvent;

/**
 * An identity was invited by {@link InvitationService#invite}. Unlike who's other events, it is
 * published synchronously inside the transaction that creates the identity, registers its address
 * and assigns its roles, before that transaction commits: a listener may write the application's
 * own data for the new identity in the same transaction, and a listener that throws rolls the whole
 * invitation back, so no identity is created and no link is sent.
 *
 * <p>The address is personal data.
 *
 * @param occurredAt when the invitation was made
 * @param identityId the new identity
 * @param address the address it was invited at, normalized
 * @param roles the names of the roles it was given; empty if none
 */
public record IdentityInvited(
    Instant occurredAt, UUID identityId, String address, Set<String> roles) implements WhoEvent {

  /** Validates the components and copies the roles. */
  public IdentityInvited {
    requireNonNull(occurredAt, "occurredAt must not be null");
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(address, "address must not be null");
    roles = Set.copyOf(requireNonNull(roles, "roles must not be null"));
  }
}
