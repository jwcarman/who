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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
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
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhoServiceTest {

  @Mock private IdentityRepository identityRepository;

  @Mock private CredentialIdentityRepository credentialIdentityRepository;

  @Mock private PermissionsResolver permissionsResolver;

  @Mock private Credential credential;

  @Mock private CredentialRemover emailRemover;

  @Mock private CredentialRemover apiKeyRemover;

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

  @Test
  void deleteIdentityRemovesEachLinkedCredentialThenTheIdentity() {
    UUID identityId = UUID.randomUUID();
    UUID email = UUID.randomUUID();
    UUID apiKey = UUID.randomUUID();
    when(identityRepository.existsById(identityId)).thenReturn(true);
    when(credentialIdentityRepository.findCredentialIdsByIdentityId(identityId))
        .thenReturn(List.of(email, apiKey));
    when(emailRemover.remove(email)).thenReturn(true);
    when(emailRemover.remove(apiKey)).thenReturn(false);
    when(apiKeyRemover.remove(apiKey)).thenReturn(true);

    boolean deleted = serviceWithRemovers().deleteIdentity(identityId);

    assertThat(deleted).isTrue();
    verify(apiKeyRemover, never()).remove(email);
    InOrder order = inOrder(credentialIdentityRepository, identityRepository);
    order.verify(credentialIdentityRepository).unlink(email);
    order.verify(credentialIdentityRepository).unlink(apiKey);
    order.verify(identityRepository).deleteById(identityId);
  }

  @Test
  void deleteIdentityUnlinksACredentialNoRemoverOwns() {
    UUID identityId = UUID.randomUUID();
    UUID passkey = UUID.randomUUID();
    when(identityRepository.existsById(identityId)).thenReturn(true);
    when(credentialIdentityRepository.findCredentialIdsByIdentityId(identityId))
        .thenReturn(List.of(passkey));

    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    assertThat(service.deleteIdentity(identityId)).isTrue();
    verify(credentialIdentityRepository).unlink(passkey);
    verify(identityRepository).deleteById(identityId);
  }

  @Test
  void deleteUnknownIdentityReturnsFalse() {
    UUID identityId = UUID.randomUUID();
    when(identityRepository.existsById(identityId)).thenReturn(false);

    boolean deleted = serviceWithRemovers().deleteIdentity(identityId);

    assertThat(deleted).isFalse();
    verify(identityRepository, never()).deleteById(identityId);
    verifyNoInteractions(credentialIdentityRepository, emailRemover, apiKeyRemover);
  }

  @Test
  void purgeOrphanedCredentialsAddsUpEveryRemover() {
    when(emailRemover.removeUnlinked()).thenReturn(2);
    when(apiKeyRemover.removeUnlinked()).thenReturn(3);

    assertThat(serviceWithRemovers().purgeOrphanedCredentials()).isEqualTo(5);
  }

  @Test
  void purgeOrphanedCredentialsWithoutRemoversDeletesNothing() {
    WhoService service =
        new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);

    assertThat(service.purgeOrphanedCredentials()).isZero();
  }

  private WhoService serviceWithRemovers() {
    return new WhoService(
        identityRepository,
        credentialIdentityRepository,
        permissionsResolver,
        List.of(emailRemover, apiKeyRemover));
  }
}
