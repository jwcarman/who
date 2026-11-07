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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Emails the owner when a passkey is added, if the owner has an address. who-passkey does not
 * depend on who-email-link, so the address comes from a lookup function, which autoconfiguration
 * backs with {@code EmailCredentialService.findOldestFor}.
 */
public class MailPasskeyAddedNotifier implements PasskeyAddedNotifier {

  private static final Logger log = LoggerFactory.getLogger(MailPasskeyAddedNotifier.class);

  static final String SUBJECT = "A passkey was added to your account";

  private final JavaMailSender mailSender;
  private final Function<UUID, Optional<String>> ownerAddress;
  private final String from;

  /**
   * Creates a new notifier.
   *
   * @param mailSender the mail sender
   * @param ownerAddress finds the address to notify for an identity, or empty if it has none
   * @param from the sender address
   */
  public MailPasskeyAddedNotifier(
      JavaMailSender mailSender, Function<UUID, Optional<String>> ownerAddress, String from) {
    this.mailSender = requireNonNull(mailSender, "mailSender must not be null");
    this.ownerAddress = requireNonNull(ownerAddress, "ownerAddress must not be null");
    this.from = requireNonNull(from, "from must not be null");
  }

  /** Sends the email. A failure is logged at WARN with the identity id and exception class only. */
  @Override
  public void passkeyAdded(UUID identityId, String label) {
    try {
      ownerAddress.apply(identityId).ifPresent(address -> send(address, label));
    } catch (RuntimeException e) {
      log.warn(
          "Could not send the passkey-added email for identity {}: {}",
          identityId,
          e.getClass().getName());
    }
  }

  private void send(String address, String label) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(address);
    message.setSubject(SUBJECT);
    message.setText(
        "A passkey named \""
            + label
            + "\" was added to your account."
            + " If you did not add it, remove it and secure your account.");
    mailSender.send(message);
  }
}
