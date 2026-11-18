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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmails;
import org.jwcarman.who.email.MustacheWhoEmailRenderer;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailPasskeyAddedNotifierTest {

  private final UUID identityId = UUID.randomUUID();
  private final List<OutgoingEmail> sent = new ArrayList<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(EmailPasskeyAddedNotifier.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  private final MustacheWhoEmailRenderer renderer =
      new MustacheWhoEmailRenderer(
          new PathMatchingResourcePatternResolver(),
          List.of("classpath:/no-app-templates/"),
          messages(),
          Map.of(),
          List.of());

  private static ResourceBundleMessageSource messages() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasenames("org/jwcarman/who/passkey/email-messages");
    messages.setFallbackToSystemLocale(false);
    return messages;
  }

  private EmailPasskeyAddedNotifier notifier(Optional<String> owner) {
    return new EmailPasskeyAddedNotifier(
        new WhoEmails(renderer, sent::add, () -> Locale.ENGLISH), id -> owner);
  }

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private void assertOneSafeWarning(String exceptionClass) {
    assertThat(logs.list).hasSize(1);
    ILoggingEvent event = logs.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .contains(identityId.toString())
        .contains(exceptionClass)
        .doesNotContain("owner@example.com");
    assertThat(event.getThrowableProxy()).isNull();
  }

  @Nested
  class PasskeyAdded {

    @Test
    void sends_a_passkey_added_message_to_the_owner() {
      notifier(Optional.of("owner@example.com")).passkeyAdded(identityId, "Laptop");

      assertThat(sent)
          .singleElement()
          .satisfies(
              email -> {
                assertThat(email.to()).isEqualTo("owner@example.com");
                assertThat(email.source())
                    .isEqualTo(new PasskeyAddedMessage("owner@example.com", "Laptop"));
              });
    }

    @Test
    void the_built_in_template_keeps_todays_wording() {
      notifier(Optional.of("owner@example.com")).passkeyAdded(identityId, "Laptop");

      OutgoingEmail email = sent.getFirst();
      assertThat(email.subject()).isEqualTo("A passkey was added to your account");
      assertThat(email.text())
          .isEqualTo(
              "A passkey named \"Laptop\" was added to your account."
                  + " If you did not add it, remove it and secure your account.");
      assertThat(email.html()).isNull();
    }

    @Test
    void an_owner_without_an_address_gets_nothing() {
      notifier(Optional.empty()).passkeyAdded(identityId, "Laptop");

      assertThat(sent).isEmpty();
      assertThat(logs.list).isEmpty();
    }

    @Test
    void a_send_failure_is_logged_not_thrown() {
      EmailPasskeyAddedNotifier failing =
          new EmailPasskeyAddedNotifier(
              new WhoEmails(
                  renderer,
                  email -> {
                    throw new IllegalStateException("could not reach owner@example.com");
                  },
                  () -> Locale.ENGLISH),
              id -> Optional.of("owner@example.com"));

      failing.passkeyAdded(identityId, "Laptop");

      assertOneSafeWarning("IllegalStateException");
    }

    @Test
    void a_notice_inside_a_transaction_is_logged_not_thrown() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        notifier(Optional.of("owner@example.com")).passkeyAdded(identityId, "Laptop");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }

      assertThat(sent).isEmpty();
      assertOneSafeWarning("IllegalStateException");
    }
  }
}
