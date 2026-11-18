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
import java.util.Set;

import org.jwcarman.who.core.email.WhoEmail;

/**
 * An email inviting a person to the application: the link and the typed code that both sign them in
 * with the invited address, which is accepting the invitation. It is what {@link InvitationService}
 * sends, so a sender can word an invitation differently from a {@link EmailLinkMessage sign-in}.
 *
 * @param address the invited address, and the recipient
 * @param link the sign-in link, carrying the token in its fragment
 * @param code the raw normalized sign-in code
 * @param expiresAt when the link and the code stop working
 * @param validFor how long the link and the code work from when the email is sent
 * @param roles the names of the roles the invitation gave the identity; empty for none
 */
public record InvitationMessage(
    String address,
    String link,
    String code,
    Instant expiresAt,
    Duration validFor,
    Set<String> roles)
    implements WhoEmail {

  /** Compact constructor: validates that no field is {@code null} and copies the roles. */
  public InvitationMessage {
    requireNonNull(address, "address must not be null");
    requireNonNull(link, "link must not be null");
    requireNonNull(code, "code must not be null");
    requireNonNull(expiresAt, "expiresAt must not be null");
    requireNonNull(validFor, "validFor must not be null");
    roles = Set.copyOf(requireNonNull(roles, "roles must not be null"));
  }

  @Override
  public String toString() {
    return "InvitationMessage[address="
        + address
        + ", link=****, code=****, expiresAt="
        + expiresAt
        + ", roles="
        + roles
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
   * Returns {@code invitation}, the name of this email's template.
   *
   * @return the template's name
   */
  @Override
  public String template() {
    return "invitation";
  }
}
