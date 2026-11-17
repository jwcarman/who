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

import java.util.UUID;

/**
 * What {@link InvitationService#invite} did: the identity it created, which stands whatever became
 * of the link, and what became of the sign-in link sent to it.
 *
 * @param identityId the invited identity
 * @param linkOutcome what became of the sign-in link
 */
public record Invitation(UUID identityId, EmailLinkOutcome linkOutcome) {

  /** Validates that neither component is null. */
  public Invitation {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(linkOutcome, "linkOutcome must not be null");
  }

  /**
   * Whether the sign-in link was sent: only {@link EmailLinkOutcome#SENT} counts. Otherwise the
   * invited person can still sign in by asking for a link from the sign-in page.
   *
   * @return {@code true} if the link was handed to the sender
   */
  public boolean linkSent() {
    return linkOutcome == EmailLinkOutcome.SENT;
  }
}
