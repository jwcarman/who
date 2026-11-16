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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailAddressChangesTest extends AbstractEmailLinkTest {

  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
  private static final String CONFIRM_URL = "https://app.test/profile";
  private static final EmailLinkSettings SETTINGS =
      new EmailLinkSettings(
          "https://app.test/signin",
          Duration.ofMinutes(5),
          Duration.ofSeconds(60),
          6,
          3,
          "an-address-change-test-secret-of-32+-chars");

  @Autowired private JdbcEmailCredentialRepository emailCredentialRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailAddressRequestRepository requests;

  private final List<WhoEmail> sent = new CopyOnWriteArrayList<>();
  private final List<WhoEvent> events = new CopyOnWriteArrayList<>();
  private EmailLinkSender sender = sent::add;
  private EmailCredentialService credentials;
  private Identity alice;

  @BeforeEach
  void setUp() {
    credentials =
        new EmailCredentialService(
            emailCredentialRepository,
            credentialIdentityRepository,
            events::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
    alice = identityRepository.save(Identity.create());
  }

  private EmailAddressChanges changes() {
    return changes(NOW);
  }

  private EmailAddressChanges changes(Instant now) {
    return new EmailAddressChanges(
        credentials,
        requests,
        email -> sender.send(email),
        SETTINGS,
        CONFIRM_URL,
        Clock.fixed(now, ZoneOffset.UTC),
        events::add);
  }

  private EmailAddressConfirmationMessage lastConfirmation() {
    return (EmailAddressConfirmationMessage) sent.getLast();
  }

  private String token() {
    String link = lastConfirmation().link();
    return link.substring(link.indexOf("#confirm-address=") + "#confirm-address=".length());
  }

  @Nested
  class Requesting {

    @Test
    void a_free_address_is_sent_a_link_and_a_code() {
      assertThat(changes().request(alice.id(), " New@Example.com ", null))
          .isEqualTo(EmailAddressRequestOutcome.SENT);

      EmailAddressConfirmationMessage message = lastConfirmation();
      assertThat(message.address()).isEqualTo("new@example.com");
      assertThat(message.link()).startsWith(CONFIRM_URL + "#confirm-address=");
      assertThat(message.code()).hasSize(6);
      assertThat(message.expiresAt()).isEqualTo(NOW.plus(SETTINGS.tokenTtl()));
      assertThat(message.change()).isFalse();
      assertThat(events)
          .containsExactly(
              new EmailAddressConfirmationRequested(
                  NOW, alice.id(), "new@example.com", false, EmailAddressRequestOutcome.SENT));
    }

    @Test
    void a_change_says_it_is_one() {
      EmailCredential old = credentials.register(alice, "old@example.com");

      changes().request(alice.id(), "new@example.com", old.id());

      assertThat(lastConfirmation().change()).isTrue();
    }

    @Test
    void an_address_someone_has_is_sent_nothing() {
      credentials.register(identityRepository.save(Identity.create()), "taken@example.com");

      assertThat(changes().request(alice.id(), "taken@example.com", null))
          .isEqualTo(EmailAddressRequestOutcome.ADDRESS_TAKEN);
      assertThat(sent).isEmpty();
    }

    @Test
    void an_address_already_yours_is_sent_nothing() {
      credentials.register(alice, "mine@example.com");

      assertThat(changes().request(alice.id(), "mine@example.com", null))
          .isEqualTo(EmailAddressRequestOutcome.ADDRESS_TAKEN);
      assertThat(sent).isEmpty();
    }

    @Test
    void something_that_is_not_an_address_is_sent_nothing() {
      assertThat(changes().request(alice.id(), "not an address", null))
          .isEqualTo(EmailAddressRequestOutcome.INVALID_ADDRESS);
      assertThat(changes().request(alice.id(), null, null))
          .isEqualTo(EmailAddressRequestOutcome.INVALID_ADDRESS);
      assertThat(sent).isEmpty();
      assertThat(events)
          .allSatisfy(e -> assertThat(((EmailAddressConfirmationRequested) e).address()).isNull());
    }

    @Test
    void a_second_request_too_soon_is_sent_nothing() {
      changes().request(alice.id(), "one@example.com", null);

      assertThat(changes().request(alice.id(), "two@example.com", null))
          .isEqualTo(EmailAddressRequestOutcome.RESEND_SUPPRESSED);
      assertThat(sent).hasSize(1);
    }

    @Test
    void a_failed_send_leaves_nothing_to_confirm() {
      List<WhoEmail> attempted = new CopyOnWriteArrayList<>();
      sender =
          email -> {
            attempted.add(email);
            throw new IllegalStateException("mail server down");
          };

      assertThat(changes().request(alice.id(), "new@example.com", null))
          .isEqualTo(EmailAddressRequestOutcome.SEND_FAILED);

      String link = ((EmailAddressConfirmationMessage) attempted.getLast()).link();
      String token = link.substring(link.indexOf('=') + 1);
      EmailAddressChanges changes = changes();
      assertThatThrownBy(() -> changes.confirmToken(alice, token))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
      sender = sent::add;
      assertThat(changes().request(alice.id(), "new@example.com", null))
          .isEqualTo(EmailAddressRequestOutcome.SENT);
    }
  }

  @Nested
  class Confirming {

    @Test
    void the_link_adds_the_address() {
      changes().request(alice.id(), "new@example.com", null);
      events.clear();

      EmailCredential added = changes().confirmToken(alice, token());

      assertThat(added.address()).isEqualTo("new@example.com");
      assertThat(credentials.isOwnedBy(added.id(), alice.id())).isTrue();
      assertThat(events).singleElement().isInstanceOf(CredentialAdded.class);
    }

    @Test
    void the_code_adds_the_address() {
      changes().request(alice.id(), "new@example.com", null);

      EmailCredential added = changes().confirmCode(alice, lastConfirmation().formattedCode());

      assertThat(credentials.isOwnedBy(added.id(), alice.id())).isTrue();
    }

    @Test
    void a_change_replaces_the_old_address_and_tells_it() {
      EmailCredential old = credentials.register(alice, "old@example.com");
      changes().request(alice.id(), "new@example.com", old.id());
      events.clear();

      EmailCredential added = changes().confirmToken(alice, token());

      assertThat(credentials.findById(old.id())).isEmpty();
      assertThat(credentials.isOwnedBy(added.id(), alice.id())).isTrue();
      assertThat(events).hasSize(2).first().isInstanceOf(CredentialAdded.class);
      assertThat(events.getLast()).isInstanceOf(CredentialRevoked.class);
      assertThat(sent.getLast())
          .isEqualTo(new EmailAddressChangedMessage("old@example.com", "new@example.com"));
    }

    @Test
    void a_failed_notice_still_changes_the_address() {
      EmailCredential old = credentials.register(alice, "old@example.com");
      changes().request(alice.id(), "new@example.com", old.id());
      String token = token();
      sender =
          email -> {
            throw new IllegalStateException("mail server down");
          };

      EmailCredential added = changes().confirmToken(alice, token);

      assertThat(credentials.findById(old.id())).isEmpty();
      assertThat(credentials.isOwnedBy(added.id(), alice.id())).isTrue();
    }

    @Test
    void another_identity_confirms_nothing() {
      Identity mallory = identityRepository.save(Identity.create());
      changes().request(alice.id(), "new@example.com", null);
      String token = token();
      String code = lastConfirmation().code();
      EmailAddressChanges changes = changes();

      assertThatThrownBy(() -> changes.confirmToken(mallory, token))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
      assertThatThrownBy(() -> changes.confirmCode(mallory, code))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
      assertThat(changes().confirmToken(alice, token).address()).isEqualTo("new@example.com");
    }

    @Test
    void a_used_link_confirms_nothing() {
      changes().request(alice.id(), "new@example.com", null);
      String token = token();
      EmailAddressChanges changes = changes();
      changes.confirmToken(alice, token);

      assertThatThrownBy(() -> changes.confirmToken(alice, token))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
    }

    @Test
    void an_expired_link_confirms_nothing() {
      changes().request(alice.id(), "new@example.com", null);
      String token = token();

      EmailAddressChanges later = changes(NOW.plus(SETTINGS.tokenTtl()));

      assertThatThrownBy(() -> later.confirmToken(alice, token))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
    }

    @Test
    void wrong_codes_run_out_and_then_even_the_right_one_fails() {
      changes().request(alice.id(), "new@example.com", null);
      String code = lastConfirmation().code();
      String wrong = code.equals("AAAAAA") ? "BBBBBB" : "AAAAAA";
      EmailAddressChanges changes = changes();

      for (int i = 0; i < SETTINGS.codeMaxAttempts(); i++) {
        assertThatThrownBy(() -> changes.confirmCode(alice, wrong))
            .isInstanceOf(InvalidEmailAddressConfirmationException.class);
      }
      assertThatThrownBy(() -> changes.confirmCode(alice, code))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
    }

    @Test
    void something_that_is_not_a_code_confirms_nothing() {
      changes().request(alice.id(), "new@example.com", null);

      EmailAddressChanges changes = changes();
      assertThatThrownBy(() -> changes.confirmCode(alice, "nope!"))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
      assertThatThrownBy(() -> changes.confirmCode(alice, null))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
      assertThatThrownBy(() -> changes.confirmToken(alice, null))
          .isInstanceOf(InvalidEmailAddressConfirmationException.class);
    }

    @Test
    void an_address_taken_meanwhile_is_taken() {
      changes().request(alice.id(), "new@example.com", null);
      String token = token();
      credentials.register(identityRepository.save(Identity.create()), "new@example.com");
      EmailAddressChanges changes = changes();

      assertThatThrownBy(() -> changes.confirmToken(alice, token))
          .isInstanceOf(EmailAddressTakenException.class);
    }

    @Test
    void an_old_address_removed_meanwhile_just_adds_the_new_one() {
      EmailCredential old = credentials.register(alice, "old@example.com");
      changes().request(alice.id(), "new@example.com", old.id());
      String token = token();
      credentials.revoke(old.id());

      EmailCredential added = changes().confirmToken(alice, token);

      assertThat(credentials.isOwnedBy(added.id(), alice.id())).isTrue();
      assertThat(sent).singleElement().isInstanceOf(EmailAddressConfirmationMessage.class);
    }
  }

  @Test
  void a_request_names_an_unknown_identity_safely() {
    assertThat(changes().request(UUID.randomUUID(), "new@example.com", null))
        .isEqualTo(EmailAddressRequestOutcome.SEND_FAILED);
  }
}
