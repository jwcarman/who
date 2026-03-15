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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhoServiceTest {

  @Mock private IdentityRepository identityRepository;

  @Mock private CredentialIdentityRepository credentialIdentityRepository;

  @Mock private PermissionsResolver permissionsResolver;

  @Mock private Credential credential;

  private UUID credentialId;

  @BeforeEach
  void setUp() {
    credentialId = UUID.randomUUID();
  }

  @Test
  void returnsEmptyWhenCredentialNotLinked() {
    when(credential.id()).thenReturn(credentialId);
    when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
        .thenReturn(Optional.empty());

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    assertThat(service.resolve(credential)).isEmpty();
  }

  @Test
  void returnsEmptyWhenIdentityNotFound() {
    UUID unknownIdentityId = UUID.randomUUID();
    when(credential.id()).thenReturn(credentialId);
    when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
        .thenReturn(Optional.of(unknownIdentityId));
    when(identityRepository.findById(unknownIdentityId)).thenReturn(Optional.empty());

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    assertThat(service.resolve(credential)).isEmpty();
  }

  @Test
  void returnsEmptyWhenIdentitySuspended() {
    Identity suspended = Identity.create(IdentityStatus.SUSPENDED);
    when(credential.id()).thenReturn(credentialId);
    when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
        .thenReturn(Optional.of(suspended.id()));
    when(identityRepository.findById(suspended.id())).thenReturn(Optional.of(suspended));

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    assertThat(service.resolve(credential)).isEmpty();
  }

  @Test
  void returnsEmptyWhenIdentityDisabled() {
    Identity disabled = Identity.create(IdentityStatus.DISABLED);
    when(credential.id()).thenReturn(credentialId);
    when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
        .thenReturn(Optional.of(disabled.id()));
    when(identityRepository.findById(disabled.id())).thenReturn(Optional.of(disabled));

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    assertThat(service.resolve(credential)).isEmpty();
  }

  @Test
  void returnsPrincipalWithPermissionsFromResolver() {
    Identity active = Identity.create();
    when(credential.id()).thenReturn(credentialId);
    when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
        .thenReturn(Optional.of(active.id()));
    when(identityRepository.findById(active.id())).thenReturn(Optional.of(active));
    when(permissionsResolver.resolve(active)).thenReturn(Set.of("read", "write"));

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    Optional<WhoPrincipal> result = service.resolve(credential);
    assertThat(result).isPresent();
    assertThat(result.get().identity()).isEqualTo(active);
    assertThat(result.get().permissions()).containsExactlyInAnyOrder("read", "write");
  }

  @Test
  void createIdentityPersistsActiveIdentityAndReturnsIt() {
    when(identityRepository.save(any(Identity.class))).thenAnswer(inv -> inv.getArgument(0));

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);
    Identity result = service.createIdentity();

    verify(identityRepository).save(any(Identity.class));
    assertThat(result.id()).isNotNull();
    assertThat(result.status()).isEqualTo(IdentityStatus.ACTIVE);
  }
}
