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

import java.time.Duration;
import java.time.Instant;

import org.jwcarman.who.core.email.WhoEmail;

/**
 * An email asking the person to confirm an address they asked to add to their identity, or to make
 * their address in place of another: the link and the typed code that both confirm it.
 *
 * @param address the address to confirm, and the recipient
 * @param link the confirmation link, carrying the token in its fragment
 * @param code the raw normalized confirmation code
 * @param expiresAt when the link and the code stop working
 * @param validFor how long the link and the code work from when the email is sent
 * @param change whether the address replaces one of the identity's addresses
 */
public record EmailAddressConfirmationMessage(
    String address, String link, String code, Instant expiresAt, Duration validFor, boolean change)
    implements WhoEmail {

  /** Compact constructor: validates that no field is {@code null}. */
  public EmailAddressConfirmationMessage {
    requireNonNull(address, "address must not be null");
    requireNonNull(link, "link must not be null");
    requireNonNull(code, "code must not be null");
    requireNonNull(expiresAt, "expiresAt must not be null");
    requireNonNull(validFor, "validFor must not be null");
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

  /**
   * Returns how long the link and the code work, for reading: {@code 15 minutes}, {@code 1 minute}.
   *
   * @return the time left, in whole minutes rounded up
   */
  public String expiresIn() {
    return EmailExpiry.describe(validFor);
  }

  /**
   * Returns {@code address-confirmation}, the name of this email's template.
   *
   * @return the template's name
   */
  @Override
  public String template() {
    return "address-confirmation";
  }
}
