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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmail;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SmtpWhoEmailSenderTest {

  record Hello(String address) implements WhoEmail {
    @Override
    public String template() {
      return "hello";
    }
  }

  private static final Hello HELLO = new Hello("ann@example.com");

  private final JavaMailSender mailSender = mock(JavaMailSender.class);
  private final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
  private final SmtpWhoEmailSender sender = new SmtpWhoEmailSender(mailSender, "no-reply@who.dev");

  @BeforeEach
  void createsRealMessages() {
    when(mailSender.createMimeMessage()).thenReturn(message);
  }

  private static void collect(Object content, List<String> leaves) throws Exception {
    if (content instanceof Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        BodyPart part = multipart.getBodyPart(i);
        if (part.getContent() instanceof Multipart) {
          collect(part.getContent(), leaves);
        } else {
          leaves.add(part.getContentType().split(";")[0] + ": " + part.getContent());
        }
      }
    }
  }

  @Nested
  class Send {

    @Test
    void from_to_and_subject_are_set() throws Exception {
      sender.send(new OutgoingEmail("ann@example.com", "Hi", "Hello", null, HELLO));

      verify(mailSender).send(message);
      assertThat(message.getFrom()[0]).hasToString("no-reply@who.dev");
      assertThat(message.getAllRecipients()[0]).hasToString("ann@example.com");
      assertThat(message.getSubject()).isEqualTo("Hi");
    }

    @Test
    void text_only_is_a_single_part() throws Exception {
      sender.send(new OutgoingEmail("ann@example.com", "Hi", "Hello", null, HELLO));
      message.saveChanges();

      assertThat(message.getContent()).isEqualTo("Hello");
    }

    @Test
    void html_adds_an_html_alternative_to_the_text() throws Exception {
      sender.send(new OutgoingEmail("ann@example.com", "Hi", "Hello", "<p>Hello</p>", HELLO));
      message.saveChanges();

      List<String> leaves = new ArrayList<>();
      collect(message.getContent(), leaves);
      assertThat(leaves).containsExactly("text/plain: Hello", "text/html: <p>Hello</p>");
    }

    @Test
    void a_send_failure_propagates() {
      MailSendException failure = new MailSendException("down");
      doThrow(failure).when(mailSender).send(any(MimeMessage.class));

      assertThatThrownBy(
              () -> sender.send(new OutgoingEmail("ann@example.com", "Hi", "Hello", null, HELLO)))
          .isSameAs(failure);
    }
  }
}
