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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailEmailLinkSenderTest {

  @Test
  void sendsPlainTextFromAndSubject() {
    JavaMailSender mailSender = mock(JavaMailSender.class);
    MailEmailLinkSender sender =
        new MailEmailLinkSender(mailSender, "noreply@app.test", "Your sign-in link");

    sender.send("owner@example.com", "https://app.test/signin#token=abc");

    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(message.capture());
    assertThat(message.getValue().getFrom()).isEqualTo("noreply@app.test");
    assertThat(message.getValue().getTo()).containsExactly("owner@example.com");
    assertThat(message.getValue().getSubject()).isEqualTo("Your sign-in link");
    assertThat(message.getValue().getText())
        .isEqualTo("Sign in: https://app.test/signin#token=abc");
  }
}
