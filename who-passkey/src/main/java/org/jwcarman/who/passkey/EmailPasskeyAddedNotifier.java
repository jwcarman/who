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

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.jwcarman.who.core.email.WhoEmails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emails a {@link PasskeyAddedMessage} to the identity's owner through {@link WhoEmails}. */
public class EmailPasskeyAddedNotifier implements PasskeyAddedNotifier {

  private static final Logger log = LoggerFactory.getLogger(EmailPasskeyAddedNotifier.class);

  private final WhoEmails emails;
  private final Function<UUID, Optional<String>> ownerAddress;

  /**
   * Creates a new notifier.
   *
   * @param emails sends the email
   * @param ownerAddress finds the address to notify for an identity, or empty if it has none
   */
  public EmailPasskeyAddedNotifier(
      WhoEmails emails, Function<UUID, Optional<String>> ownerAddress) {
    this.emails = requireNonNull(emails, "emails must not be null");
    this.ownerAddress = requireNonNull(ownerAddress, "ownerAddress must not be null");
  }

  /**
   * Sends the email. Any failure, including being called inside a transaction, is logged at WARN
   * with the identity id and exception class only, and not rethrown.
   */
  @Override
  public void passkeyAdded(UUID identityId, String label) {
    try {
      ownerAddress
          .apply(identityId)
          .ifPresent(address -> emails.send(new PasskeyAddedMessage(address, label)));
    } catch (RuntimeException e) {
      log.warn(
          "Could not send the passkey-added email for identity {}: {}",
          identityId,
          e.getClass().getName());
    }
  }
}
