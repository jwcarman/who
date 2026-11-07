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

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Sends sign-in links as plain-text email through a {@link JavaMailSender}. */
public class MailEmailLinkSender implements EmailLinkSender {

  private final JavaMailSender mailSender;
  private final String from;
  private final String subject;

  /**
   * Creates a new sender.
   *
   * @param mailSender the mail sender
   * @param from the sender address
   * @param subject the subject line
   */
  public MailEmailLinkSender(JavaMailSender mailSender, String from, String subject) {
    this.mailSender = requireNonNull(mailSender, "mailSender must not be null");
    this.from = requireNonNull(from, "from must not be null");
    this.subject = requireNonNull(subject, "subject must not be null");
  }

  @Override
  public void send(String address, String link) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(address);
    message.setSubject(subject);
    message.setText("Sign in: " + link);
    mailSender.send(message);
  }
}
