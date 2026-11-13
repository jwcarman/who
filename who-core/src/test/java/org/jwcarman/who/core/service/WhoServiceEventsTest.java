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
package org.jwcarman.who.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.IdentityCreated;
import org.jwcarman.who.core.event.IdentityDeactivated;
import org.jwcarman.who.core.event.IdentityDeleted;
import org.jwcarman.who.core.event.IdentityReactivated;
import org.jwcarman.who.core.event.OrphanedCredentialsPurged;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.CredentialRemover;

/** The events {@link WhoService} publishes, and the reasons {@code resolveDetailed} reports. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoServiceEventsTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:15:30Z");

  private final Map<UUID, Identity> identities = new HashMap<>();
  private final Map<UUID, UUID> links = new HashMap<>();
  private final List<WhoEvent> events = new ArrayList<>();
  private final Map<UUID, Credential> removable = new HashMap<>();
  private int unlinkedPurgeCount;
  private WhoService who;

  @BeforeEach
  void setUp() {
    CredentialRemover remover =
        new CredentialRemover() {
          @Override
          public boolean remove(UUID credentialId) {
            return removable.containsKey(credentialId);
          }

          @Override
          public int removeUnlinked() {
            return unlinkedPurgeCount;
          }

          @Override
          public Optional<Credential> find(UUID credentialId) {
            return Optional.ofNullable(removable.get(credentialId));
          }
        };
    who =
        new WhoService(
            new InMemoryIdentities(),
            new InMemoryLinks(),
            identity -> Set.of("read"),
            List.of(remover),
            events::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Credential credential(UUID id, CredentialKind kind, String label) {
    return new Credential() {
      @Override
      public UUID id() {
        return id;
      }

      @Override
      public CredentialKind kind() {
        return kind;
      }

      @Override
      public Optional<String> label() {
        return Optional.ofNullable(label);
      }
    };
  }

  @Nested
  class Lifecycle {

    @Test
    void create_identity_publishes_identity_created() {
      Identity identity = who.createIdentity();

      assertThat(events).containsExactly(new IdentityCreated(NOW, identity.id()));
    }

    @Test
    void deactivate_publishes_once_and_not_for_an_already_disabled_identity() {
      Identity identity = who.createIdentity();
      events.clear();

      assertThat(who.deactivate(identity.id())).isTrue();
      assertThat(who.deactivate(identity.id())).isTrue();

      assertThat(events).containsExactly(new IdentityDeactivated(NOW, identity.id()));
      assertThat(who.deactivate(UUID.randomUUID())).isFalse();
      assertThat(events).hasSize(1);
    }

    @Test
    void reactivate_publishes_identity_reactivated_only_when_status_changes() {
      Identity identity = who.createIdentity();
      who.reactivate(identity.id());
      assertThat(events).hasSize(1);

      who.deactivate(identity.id());
      events.clear();
      who.reactivate(identity.id());
      who.reactivate(identity.id());

      assertThat(events).containsExactly(new IdentityReactivated(NOW, identity.id()));
    }

    @Test
    void delete_identity_publishes_credential_revoked_per_credential_then_identity_deleted() {
      Identity identity = who.createIdentity();
      UUID known = UUID.randomUUID();
      UUID unknown = UUID.randomUUID();
      removable.put(known, credential(known, CredentialKind.API_KEY, "ci"));
      links.put(known, identity.id());
      links.put(unknown, identity.id());
      events.clear();

      assertThat(who.deleteIdentity(identity.id())).isTrue();

      assertThat(events)
          .hasSize(3)
          .containsExactlyInAnyOrder(
              new CredentialRevoked(NOW, identity.id(), known, CredentialKind.API_KEY, "ci"),
              new CredentialRevoked(NOW, identity.id(), unknown, CredentialKind.OTHER, null),
              new IdentityDeleted(NOW, identity.id()));
      assertThat(events.get(2)).isEqualTo(new IdentityDeleted(NOW, identity.id()));
    }

    @Test
    void delete_identity_publishes_nothing_for_a_missing_identity() {
      assertThat(who.deleteIdentity(UUID.randomUUID())).isFalse();

      assertThat(events).isEmpty();
    }

    @Test
    void purge_publishes_count_only_when_nonzero() {
      assertThat(who.purgeOrphanedCredentials()).isZero();
      assertThat(events).isEmpty();

      unlinkedPurgeCount = 3;
      assertThat(who.purgeOrphanedCredentials()).isEqualTo(3);

      assertThat(events).containsExactly(new OrphanedCredentialsPurged(NOW, 3));
    }
  }

  @Nested
  class Resolving {

    @Test
    void resolve_detailed_distinguishes_not_linked_missing_identity_and_inactive() {
      Identity active = who.createIdentity();
      Identity disabled = who.createIdentity();
      who.deactivate(disabled.id());
      UUID activeCredential = UUID.randomUUID();
      UUID disabledCredential = UUID.randomUUID();
      UUID danglingCredential = UUID.randomUUID();
      UUID missingIdentity = UUID.randomUUID();
      links.put(activeCredential, active.id());
      links.put(disabledCredential, disabled.id());
      links.put(danglingCredential, missingIdentity);

      assertThat(who.resolveDetailed(credential(UUID.randomUUID(), CredentialKind.OTHER, null)))
          .isEqualTo(new Resolution.NotLinked());
      assertThat(who.resolveDetailed(credential(danglingCredential, CredentialKind.OTHER, null)))
          .isEqualTo(new Resolution.NoIdentity(missingIdentity));
      assertThat(who.resolveDetailed(credential(disabledCredential, CredentialKind.OTHER, null)))
          .isEqualTo(new Resolution.Inactive(disabled.id()));
      assertThat(who.resolveDetailed(credential(activeCredential, CredentialKind.OTHER, null)))
          .isInstanceOfSatisfying(
              Resolution.Resolved.class,
              resolved -> {
                assertThat(resolved.value().identity()).isEqualTo(active);
                assertThat(resolved.value().permissions()).containsExactly("read");
              });
    }

    @Test
    void resolve_still_returns_empty_for_every_failure() {
      Identity disabled = who.createIdentity();
      who.deactivate(disabled.id());
      UUID disabledCredential = UUID.randomUUID();
      UUID danglingCredential = UUID.randomUUID();
      links.put(disabledCredential, disabled.id());
      links.put(danglingCredential, UUID.randomUUID());

      assertThat(who.resolve(credential(UUID.randomUUID(), CredentialKind.OTHER, null))).isEmpty();
      assertThat(who.resolve(credential(danglingCredential, CredentialKind.OTHER, null))).isEmpty();
      assertThat(who.resolve(credential(disabledCredential, CredentialKind.OTHER, null))).isEmpty();
    }

    @Test
    void resolution_exposes_the_principal_only_when_resolved() {
      Identity identity = Identity.create();
      WhoPrincipal principal = new WhoPrincipal(identity, Set.of());

      assertThat(new Resolution.Resolved(principal).principal()).contains(principal);
      assertThat(new Resolution.NotLinked().principal()).isEmpty();
      assertThat(new Resolution.NoIdentity(identity.id()).principal()).isEmpty();
      assertThat(new Resolution.Inactive(identity.id()).principal()).isEmpty();
    }
  }

  private final class InMemoryIdentities implements IdentityRepository {
    @Override
    public Optional<Identity> findById(UUID id) {
      return Optional.ofNullable(identities.get(id));
    }

    @Override
    public Identity save(Identity identity) {
      identities.put(identity.id(), identity);
      return identity;
    }

    @Override
    public boolean existsById(UUID id) {
      return identities.containsKey(id);
    }

    @Override
    public void deleteById(UUID id) {
      identities.remove(id);
    }
  }

  private final class InMemoryLinks implements CredentialIdentityRepository {
    @Override
    public Optional<UUID> findIdentityIdByCredentialId(UUID credentialId) {
      return Optional.ofNullable(links.get(credentialId));
    }

    @Override
    public List<UUID> findCredentialIdsByIdentityId(UUID identityId) {
      return links.entrySet().stream()
          .filter(entry -> entry.getValue().equals(identityId))
          .map(Map.Entry::getKey)
          .toList();
    }

    @Override
    public void link(UUID credentialId, UUID identityId) {
      links.put(credentialId, identityId);
    }

    @Override
    public void unlink(UUID credentialId) {
      links.remove(credentialId);
    }
  }
}
