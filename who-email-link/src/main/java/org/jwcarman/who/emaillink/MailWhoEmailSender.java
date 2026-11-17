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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends who's emails as plain text through a {@link JavaMailSender}. */
public class MailWhoEmailSender implements WhoEmailSender {

  private final JavaMailSender mailSender;
  private final String from;
  private final String subject;
  private final String confirmationSubject;
  private final String changedSubject;
  private final Clock clock;

  /**
   * Creates a new sender.
   *
   * @param mailSender the mail sender
   * @param from the sender address
   * @param subject the subject line of a sign-in email
   * @param clock the clock used to tell how long the link and code remain valid
   */
  public MailWhoEmailSender(JavaMailSender mailSender, String from, String subject, Clock clock) {
    this(
        mailSender,
        from,
        subject,
        "Confirm your email address",
        "Your email address was changed",
        clock);
  }

  /**
   * Creates a new sender.
   *
   * @param mailSender the mail sender
   * @param from the sender address
   * @param subject the subject line of a sign-in email
   * @param confirmationSubject the subject line of an address confirmation
   * @param changedSubject the subject line of the notice to a replaced address
   * @param clock the clock used to tell how long the link and code remain valid
   */
  public MailWhoEmailSender(
      JavaMailSender mailSender,
      String from,
      String subject,
      String confirmationSubject,
      String changedSubject,
      Clock clock) {
    this.mailSender = requireNonNull(mailSender, "mailSender must not be null");
    this.from = requireNonNull(from, "from must not be null");
    this.subject = requireNonNull(subject, "subject must not be null");
    this.confirmationSubject =
        requireNonNull(confirmationSubject, "confirmationSubject must not be null");
    this.changedSubject = requireNonNull(changedSubject, "changedSubject must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  @Override
  public void send(WhoEmail email) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email.address());
    switch (email) {
      case EmailLinkMessage signIn -> {
        message.setSubject(subject);
        message.setText(signInBody(signIn));
      }
      case EmailAddressConfirmationMessage confirmation -> {
        message.setSubject(confirmationSubject);
        message.setText(confirmationBody(confirmation));
      }
      case EmailAddressChangedMessage changed -> {
        message.setSubject(changedSubject);
        message.setText(changedBody(changed));
      }
    }
    mailSender.send(message);
  }

  private String signInBody(EmailLinkMessage message) {
    return "Sign in: "
        + message.link()
        + "\n\nOr enter this code: "
        + message.formattedCode()
        + "\n\nThe link and the code expire in "
        + minutes(message.expiresAt())
        + " and work once."
        + "\n\nIf you didn't ask to sign in, ignore this email. Never share this code with anyone.";
  }

  private String confirmationBody(EmailAddressConfirmationMessage message) {
    return (message.change()
            ? "Make this your account's email address, in place of the old one: "
            : "Add this address to your account: ")
        + message.link()
        + "\n\nOr enter this code: "
        + message.formattedCode()
        + "\n\nThe link and the code expire in "
        + minutes(message.expiresAt())
        + " and work once, while you are signed in."
        + "\n\nIf you didn't ask to add this address, ignore this email and nothing changes."
        + " Never share this code with anyone.";
  }

  private static String changedBody(EmailAddressChangedMessage message) {
    return "Your account's email address was changed to "
        + message.maskedNewAddress()
        + ". This address no longer signs in."
        + "\n\nIf you didn't make this change, someone else may have access to your account:"
        + " contact the site right away.";
  }

  private String minutes(Instant expiresAt) {
    long minutes = minutesLeft(expiresAt);
    return minutes + (minutes == 1 ? " minute" : " minutes");
  }

  private long minutesLeft(Instant expiresAt) {
    Duration left = Duration.between(clock.instant(), expiresAt);
    long wholeMinutes = left.toMinutes();
    long minutes = left.minusMinutes(wholeMinutes).isPositive() ? wholeMinutes + 1 : wholeMinutes;
    return Math.max(1, minutes);
  }
}
