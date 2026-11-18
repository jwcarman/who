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
package org.jwcarman.who.core.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoEmailsTest {

  record Hello(String address) implements WhoEmail {
    @Override
    public String template() {
      return "hello";
    }
  }

  private static final Hello HELLO = new Hello("ann@example.com");

  private final List<Locale> renderedIn = new ArrayList<>();
  private final List<OutgoingEmail> sent = new ArrayList<>();

  private final WhoEmailRenderer renderer =
      (email, locale) -> {
        renderedIn.add(locale);
        return new OutgoingEmail(email.address(), "Hi", "Hello there", null, email);
      };
  private final WhoEmailSender sender = sent::add;

  @Nested
  class Send {

    @Test
    void renders_in_the_supplied_locale_then_sends() {
      new WhoEmails(renderer, sender, () -> Locale.FRENCH).send(HELLO);

      assertThat(renderedIn).containsExactly(Locale.FRENCH);
      assertThat(sent)
          .containsExactly(new OutgoingEmail("ann@example.com", "Hi", "Hello there", null, HELLO));
    }

    @Test
    void refuses_inside_a_transaction() {
      WhoEmails emails = new WhoEmails(renderer, sender, () -> Locale.ENGLISH);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> emails.send(HELLO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("hello");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      assertThat(renderedIn).isEmpty();
      assertThat(sent).isEmpty();
    }

    @Test
    void a_render_failure_is_thrown_and_nothing_is_sent() {
      IllegalStateException failure = new IllegalStateException("no template");
      WhoEmails emails =
          new WhoEmails(
              (email, locale) -> {
                throw failure;
              },
              sender,
              () -> Locale.ENGLISH);

      assertThatThrownBy(() -> emails.send(HELLO)).isSameAs(failure);
      assertThat(sent).isEmpty();
    }

    @Test
    void a_send_failure_is_thrown_as_is() {
      RuntimeException failure = new RuntimeException("smtp down");
      WhoEmails emails =
          new WhoEmails(
              renderer,
              email -> {
                throw failure;
              },
              () -> Locale.ENGLISH);

      assertThatThrownBy(() -> emails.send(HELLO)).isSameAs(failure);
    }
  }
}
