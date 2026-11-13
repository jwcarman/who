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

import java.time.Clock;
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
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.OrphanedCredentialsPurged;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** The credential events {@link EmailCredentialService} publishes. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailCredentialEventsTest extends AbstractEmailLinkTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

  @Autowired private JdbcEmailCredentialRepository emailCredentialRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private IdentityRepository identityRepository;

  private final List<WhoEvent> events = new CopyOnWriteArrayList<>();
  private EmailCredentialService service;
  private Identity identity;

  @BeforeEach
  void setUp() {
    service =
        new EmailCredentialService(
            emailCredentialRepository,
            credentialIdentityRepository,
            events::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
    identity = identityRepository.save(Identity.create());
  }

  @Nested
  class Registering {

    @Test
    void register_publishes_credential_added_with_the_address() {
      EmailCredential credential = service.register(identity, "Owner@Example.com");

      assertThat(events)
          .containsExactly(
              new CredentialAdded(
                  NOW, identity.id(), credential.id(), CredentialKind.EMAIL, "owner@example.com"));
    }

    @Test
    void registering_the_identitys_own_address_again_publishes_nothing() {
      service.register(identity, "again@example.com");
      events.clear();

      service.register(identity, "again@example.com");

      assertThat(events).isEmpty();
    }

    @Test
    void replacing_an_orphan_publishes_its_purge_first() {
      Identity deleted = identityRepository.save(Identity.create());
      EmailCredential orphan = service.register(deleted, "orphan@example.com");
      // deleted without WhoService.deleteIdentity: the link cascades, the credential stays
      identityRepository.deleteById(deleted.id());
      events.clear();

      EmailCredential replacement = service.register(identity, "orphan@example.com");

      assertThat(replacement.id()).isNotEqualTo(orphan.id());
      // the orphan belongs to no identity, so its deletion is reported as a purge of one
      assertThat(events)
          .containsExactly(
              new OrphanedCredentialsPurged(NOW, 1),
              new CredentialAdded(
                  NOW,
                  identity.id(),
                  replacement.id(),
                  CredentialKind.EMAIL,
                  "orphan@example.com"));
    }
  }

  @Nested
  class With_an_unsafe_address {

    private static final String UNSAFE = "bell\u0007@example.com";

    @Test
    void registering_it_publishes_credential_added_without_the_address() {
      EmailCredential credential = service.register(identity, UNSAFE);

      assertThat(credential.address()).isEqualTo(UNSAFE);
      assertThat(events)
          .containsExactly(
              new CredentialAdded(NOW, identity.id(), credential.id(), CredentialKind.EMAIL, null));
    }

    @Test
    void revoking_it_publishes_credential_revoked_without_the_address() {
      EmailCredential credential = service.register(identity, UNSAFE);
      events.clear();

      service.revoke(credential.id());

      assertThat(events)
          .containsExactly(
              new CredentialRevoked(
                  NOW, identity.id(), credential.id(), CredentialKind.EMAIL, null));
    }

    @Test
    void its_credential_has_no_label() {
      assertThat(new EmailCredential(UUID.randomUUID(), UNSAFE).label()).isEmpty();
      assertThat(new EmailCredential(UUID.randomUUID(), "a".repeat(321)).label()).isEmpty();
    }
  }

  @Nested
  class Revoking {

    @Test
    void revoke_publishes_credential_revoked() {
      EmailCredential credential = service.register(identity, "gone@example.com");
      events.clear();

      service.revoke(credential.id());

      assertThat(events)
          .containsExactly(
              new CredentialRevoked(
                  NOW, identity.id(), credential.id(), CredentialKind.EMAIL, "gone@example.com"));
    }

    @Test
    void revoking_an_unknown_id_publishes_nothing() {
      service.revoke(UUID.randomUUID());

      assertThat(events).isEmpty();
    }
  }

  @Nested
  class As_a_credential {

    @Test
    void an_email_credential_is_of_kind_email_labelled_with_its_address() {
      EmailCredential credential = EmailCredential.create("Label@Example.com");

      assertThat(credential.kind()).isEqualTo(CredentialKind.EMAIL);
      assertThat(credential.label()).contains("label@example.com");
    }

    @Test
    void the_repository_finds_a_credential_for_identity_deletion() {
      EmailCredential credential = service.register(identity, "found@example.com");

      assertThat(emailCredentialRepository.find(credential.id())).contains(credential);
      assertThat(emailCredentialRepository.find(UUID.randomUUID())).isEmpty();
    }
  }
}
