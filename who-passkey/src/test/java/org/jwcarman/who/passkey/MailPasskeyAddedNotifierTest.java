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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class MailPasskeyAddedNotifierTest {

  private final UUID identityId = UUID.randomUUID();
  private final JavaMailSender mailSender = mock(JavaMailSender.class);
  private final Logger logger = (Logger) LoggerFactory.getLogger(MailPasskeyAddedNotifier.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  @Test
  void emailsTheOwnersAddress() {
    MailPasskeyAddedNotifier notifier =
        new MailPasskeyAddedNotifier(
            mailSender, id -> Optional.of("owner@example.com"), "noreply@app.test");

    notifier.passkeyAdded(identityId, "Laptop");

    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(message.capture());
    assertThat(message.getValue().getFrom()).isEqualTo("noreply@app.test");
    assertThat(message.getValue().getTo()).containsExactly("owner@example.com");
    assertThat(message.getValue().getSubject()).isEqualTo("A passkey was added to your account");
    assertThat(message.getValue().getText()).contains("Laptop");
  }

  @Test
  void sendsNothingWhenTheOwnerHasNoAddress() {
    MailPasskeyAddedNotifier notifier =
        new MailPasskeyAddedNotifier(mailSender, id -> Optional.empty(), "noreply@app.test");

    notifier.passkeyAdded(identityId, "Laptop");

    verifyNoInteractions(mailSender);
  }

  @Test
  void failuresAreLoggedWithTheIdentityAndExceptionClassOnly() {
    doThrow(new MailSendException("could not reach owner@example.com"))
        .when(mailSender)
        .send(any(SimpleMailMessage.class));
    MailPasskeyAddedNotifier notifier =
        new MailPasskeyAddedNotifier(
            mailSender, id -> Optional.of("owner@example.com"), "noreply@app.test");

    notifier.passkeyAdded(identityId, "Laptop");

    assertThat(logs.list).hasSize(1);
    ILoggingEvent event = logs.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains(identityId.toString())
        .contains("MailSendException")
        .doesNotContain("owner@example.com");
    assertThat(event.getThrowableProxy()).isNull();
  }
}
