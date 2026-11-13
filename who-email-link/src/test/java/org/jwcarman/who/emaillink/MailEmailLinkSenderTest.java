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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailEmailLinkSenderTest {

  private static final Instant EXPIRES_AT = Instant.parse("2026-11-07T10:15:00Z");
  private static final String LINK = "https://app.test/signin#token=abc";

  private final JavaMailSender mailSender = mock(JavaMailSender.class);

  private SimpleMailMessage sendWithTimeLeft(Duration left) {
    Clock clock = Clock.fixed(EXPIRES_AT.minus(left), ZoneOffset.UTC);
    MailEmailLinkSender sender =
        new MailEmailLinkSender(mailSender, "noreply@app.test", "Your sign-in link", clock);
    sender.send(new EmailLinkMessage("owner@example.com", LINK, "K7F2QM", EXPIRES_AT));
    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(message.capture());
    return message.getValue();
  }

  @Test
  void sendsPlainTextFromSubjectLinkAndCode() {
    SimpleMailMessage message = sendWithTimeLeft(Duration.ofMinutes(5));

    assertThat(message.getFrom()).isEqualTo("noreply@app.test");
    assertThat(message.getTo()).containsExactly("owner@example.com");
    assertThat(message.getSubject()).isEqualTo("Your sign-in link");
    assertThat(message.getText())
        .isEqualTo(
            "Sign in: "
                + LINK
                + "\n\nOr enter this code: K7F-2QM\n\nThe link and the code expire in 5 minutes"
                + " and work once.\n\nIf you didn't ask to sign in, ignore this email. Never share"
                + " this code with anyone.");
  }

  @Test
  void lessThanAMinuteLeftReadsOneMinute() {
    SimpleMailMessage message = sendWithTimeLeft(Duration.ofSeconds(30));

    assertThat(message.getText()).contains("expire in 1 minute and work once.");
  }
}
