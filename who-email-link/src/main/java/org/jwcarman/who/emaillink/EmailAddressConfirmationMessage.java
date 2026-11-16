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

/**
 * An email asking the person to confirm an address they asked to add to their identity, or to make
 * their address in place of another: the link and the typed code that both confirm it.
 *
 * @param address the address to confirm, and the recipient
 * @param link the confirmation link, carrying the token in its fragment
 * @param code the raw normalized confirmation code
 * @param expiresAt when the link and the code stop working
 * @param change whether the address replaces one of the identity's addresses
 */
public record EmailAddressConfirmationMessage(
    String address, String link, String code, Instant expiresAt, boolean change)
    implements WhoEmail {

  /** Compact constructor: validates that no field is {@code null}. */
  public EmailAddressConfirmationMessage {
    requireNonNull(address, "address must not be null");
    requireNonNull(link, "link must not be null");
    requireNonNull(code, "code must not be null");
    requireNonNull(expiresAt, "expiresAt must not be null");
  }

  @Override
  public String toString() {
    return "EmailAddressConfirmationMessage[address="
        + address
        + ", link=****, code=****, expiresAt="
        + expiresAt
        + ", change="
        + change
        + "]";
  }

  /**
   * Returns the code laid out for reading, for example {@code K7F-2QM}.
   *
   * @return the formatted code
   */
  public String formattedCode() {
    return EmailCodes.format(code);
  }
}
