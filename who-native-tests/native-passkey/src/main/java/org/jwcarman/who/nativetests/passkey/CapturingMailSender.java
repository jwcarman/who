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
package org.jwcarman.who.nativetests.passkey;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Keeps the last plain-text email to each address in memory instead of sending it, so the test
 * reads sign-in links and codes without a mail server. who's own {@code MailEmailLinkSender} sends
 * through it, as it would through Spring Boot's.
 */
@Component
class CapturingMailSender implements JavaMailSender {

  private final Map<String, String> lastByAddress = new ConcurrentHashMap<>();

  @Override
  public void send(SimpleMailMessage... messages) {
    for (SimpleMailMessage message : messages) {
      for (String to : message.getTo()) {
        lastByAddress.put(to, message.getText());
      }
    }
  }

  Optional<String> lastTo(String address) {
    return Optional.ofNullable(lastByAddress.get(address));
  }

  @Override
  public MimeMessage createMimeMessage() {
    throw new UnsupportedOperationException("who sends sign-in emails as plain text");
  }

  @Override
  public MimeMessage createMimeMessage(InputStream contentStream) {
    throw new UnsupportedOperationException("who sends sign-in emails as plain text");
  }

  @Override
  public void send(MimeMessage... mimeMessages) {
    throw new UnsupportedOperationException("who sends sign-in emails as plain text");
  }
}
