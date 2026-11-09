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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.PermissionsResolver;

/** Deactivating and reactivating identities, against an in-memory identity store. */
class WhoServiceStatusTest {

  private final Map<UUID, Identity> identities = new HashMap<>();
  private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
  private final PermissionsResolver permissions = mock(PermissionsResolver.class);
  private final Credential credential = mock(Credential.class);
  private WhoService who;
  private Identity identity;

  @BeforeEach
  void setUp() {
    who = new WhoService(new InMemoryIdentities(), links, permissions);
    identity = who.createIdentity();
    UUID credentialId = UUID.randomUUID();
    when(credential.id()).thenReturn(credentialId);
    when(links.findIdentityIdByCredentialId(credentialId)).thenReturn(Optional.of(identity.id()));
    when(permissions.resolve(any())).thenReturn(Set.of());
  }

  @Test
  void deactivateDisablesTheIdentitySoItNoLongerResolves() {
    assertThat(who.resolve(credential)).isPresent();

    assertThat(who.deactivate(identity.id())).isTrue();

    assertThat(identities.get(identity.id()).status()).isEqualTo(IdentityStatus.DISABLED);
    assertThat(who.resolve(credential)).isEmpty();
  }

  @Test
  void reactivateRestoresResolution() {
    who.deactivate(identity.id());

    assertThat(who.reactivate(identity.id())).isTrue();

    assertThat(identities.get(identity.id()).status()).isEqualTo(IdentityStatus.ACTIVE);
    assertThat(who.resolve(credential)).isPresent();
  }

  @Test
  void deactivateAnUnknownIdentityReturnsFalse() {
    assertThat(who.deactivate(UUID.randomUUID())).isFalse();
    assertThat(who.reactivate(UUID.randomUUID())).isFalse();
  }

  @Test
  void deactivateTwiceIsHarmless() {
    who.deactivate(identity.id());

    assertThat(who.deactivate(identity.id())).isTrue();
    assertThat(identities.get(identity.id()).status()).isEqualTo(IdentityStatus.DISABLED);
  }

  @Test
  void aNullIdIsRefused() {
    assertThatNullPointerException().isThrownBy(() -> who.deactivate(null));
    assertThatNullPointerException().isThrownBy(() -> who.reactivate(null));
  }

  private final class InMemoryIdentities implements IdentityRepository {
    @Override
    public Optional<Identity> findById(UUID id) {
      return Optional.ofNullable(identities.get(id));
    }

    @Override
    public Identity save(Identity saved) {
      identities.put(saved.id(), saved);
      return saved;
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
}
