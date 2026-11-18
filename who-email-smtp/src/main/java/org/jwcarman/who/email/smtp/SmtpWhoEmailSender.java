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
package org.jwcarman.who.email.smtp;

import static java.util.Objects.requireNonNull;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Sends who's emails over SMTP through Spring's {@link JavaMailSender}: a text-only message, or a
 * multipart one with the HTML as an alternative to the text when the email has HTML.
 */
public class SmtpWhoEmailSender implements WhoEmailSender {

  private final JavaMailSender mailSender;
  private final String from;

  /**
   * Creates the sender.
   *
   * @param mailSender sends the messages
   * @param from the address every email comes from
   */
  public SmtpWhoEmailSender(JavaMailSender mailSender, String from) {
    this.mailSender = requireNonNull(mailSender, "mailSender must not be null");
    this.from = requireNonNull(from, "from must not be null");
  }

  /**
   * Sends the email.
   *
   * @throws MailException if it could not be built or sent
   */
  @Override
  public void send(OutgoingEmail email) {
    MimeMessage message = mailSender.createMimeMessage();
    try {
      MimeMessageHelper helper = new MimeMessageHelper(message, email.html() != null, "UTF-8");
      helper.setFrom(from);
      helper.setTo(email.to());
      helper.setSubject(email.subject());
      if (email.html() == null) {
        helper.setText(email.text());
      } else {
        helper.setText(email.text(), email.html());
      }
    } catch (MessagingException e) {
      throw new MailPreparationException(
          "Could not build the " + email.source().template() + " email", e);
    }
    mailSender.send(message);
  }
}
