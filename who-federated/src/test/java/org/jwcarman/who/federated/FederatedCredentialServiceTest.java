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
package org.jwcarman.who.federated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;

/** The events {@link FederatedCredentialService} publishes for credential changes. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FederatedCredentialServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String ISSUER = "https://issuer.example.com";
  private static final String SUBJECT = "user-123";

  private final List<WhoEvent> events = new CopyOnWriteArrayList<>();

  private final FederatedCredentialRepository repository =
      mock(FederatedCredentialRepository.class);
  private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
  private final FederatedCredentialService service =
      new FederatedCredentialService(repository, links, events::add, CLOCK);

  @Test
  void link_publishes_credential_added_with_the_issuer() {
    Identity identity = Identity.create();
    when(repository.save(any(FederatedCredential.class))).thenAnswer(call -> call.getArgument(0));

    FederatedCredential credential = service.link(identity, ISSUER, SUBJECT);

    verify(links).link(credential.id(), identity.id());
    assertThat(events)
        .containsExactly(
            new CredentialAdded(
                NOW, identity.id(), credential.id(), CredentialKind.FEDERATED, ISSUER))
        .noneMatch(event -> event.toString().contains(SUBJECT));
  }

  @Test
  void unlink_publishes_credential_revoked() {
    UUID identityId = UUID.randomUUID();
    FederatedCredential credential = FederatedCredential.create(ISSUER, SUBJECT);
    when(repository.findById(credential.id())).thenReturn(Optional.of(credential));
    when(links.findIdentityIdByCredentialId(credential.id())).thenReturn(Optional.of(identityId));

    service.unlink(credential.id());

    verify(repository).deleteById(credential.id());
    verify(links).unlink(credential.id());
    assertThat(events)
        .containsExactly(
            new CredentialRevoked(
                NOW, identityId, credential.id(), CredentialKind.FEDERATED, ISSUER));
  }

  @Test
  void unlinking_an_id_that_is_linked_to_nothing_publishes_nothing() {
    UUID unknown = UUID.randomUUID();
    when(links.findIdentityIdByCredentialId(unknown)).thenReturn(Optional.empty());

    service.unlink(unknown);

    assertThat(events).isEmpty();
  }

  @Test
  void a_repository_that_cannot_find_by_id_still_publishes_revoked_with_no_label() {
    UUID identityId = UUID.randomUUID();
    UUID credentialId = UUID.randomUUID();
    FederatedCredentialRepository defaulting = mock(FederatedCredentialRepository.class);
    when(defaulting.findById(credentialId)).thenCallRealMethod();
    when(links.findIdentityIdByCredentialId(credentialId)).thenReturn(Optional.of(identityId));

    new FederatedCredentialService(defaulting, links, events::add, CLOCK).unlink(credentialId);

    assertThat(events)
        .containsExactly(
            new CredentialRevoked(NOW, identityId, credentialId, CredentialKind.FEDERATED, null));
  }

  @Test
  void link_existing_links_a_saved_row_and_publishes_credential_added() {
    Identity identity = Identity.create();
    FederatedCredential saved = FederatedCredential.create(ISSUER, SUBJECT);

    FederatedCredential linked = service.linkExisting(identity, saved);

    assertThat(linked).isEqualTo(saved);
    verify(links).link(saved.id(), identity.id());
    verify(repository, never()).save(any(FederatedCredential.class));
    assertThat(events)
        .containsExactly(
            new CredentialAdded(NOW, identity.id(), saved.id(), CredentialKind.FEDERATED, ISSUER));
  }
}
