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

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhoServiceTest {

    @Mock
    private IdentityRepository identityRepository;

    @Mock
    private CredentialIdentityRepository credentialIdentityRepository;

    @Mock
    private Credential credential;

    private UUID credentialId;
    private UUID identityId;

    @BeforeEach
    void setUp() {
        credentialId = UUID.randomUUID();
        identityId = UUID.randomUUID();
        when(credential.id()).thenReturn(credentialId);
    }

    @Test
    void returnsEmptyWhenCredentialNotLinked() {
        when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
                .thenReturn(Optional.empty());

        WhoService service = new WhoService(identityRepository, credentialIdentityRepository, List.of());

        assertThat(service.resolve(credential)).isEmpty();
    }

    @Test
    void returnsEmptyWhenIdentityNotFound() {
        when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
                .thenReturn(Optional.of(identityId));
        when(identityRepository.findById(identityId)).thenReturn(Optional.empty());

        WhoService service = new WhoService(identityRepository, credentialIdentityRepository, List.of());

        assertThat(service.resolve(credential)).isEmpty();
    }

    @Test
    void returnsEmptyWhenIdentitySuspended() {
        Identity suspended = Identity.create(identityId, IdentityStatus.SUSPENDED);
        when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
                .thenReturn(Optional.of(identityId));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(suspended));

        WhoService service = new WhoService(identityRepository, credentialIdentityRepository, List.of());

        assertThat(service.resolve(credential)).isEmpty();
    }

    @Test
    void returnsEmptyWhenIdentityDisabled() {
        Identity disabled = Identity.create(identityId, IdentityStatus.DISABLED);
        when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
                .thenReturn(Optional.of(identityId));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(disabled));

        WhoService service = new WhoService(identityRepository, credentialIdentityRepository, List.of());

        assertThat(service.resolve(credential)).isEmpty();
    }

    @Test
    void returnsPrincipalWithEmptyPermissionsWhenNoResolvers() {
        Identity active = Identity.create(identityId, IdentityStatus.ACTIVE);
        when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
                .thenReturn(Optional.of(identityId));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(active));

        WhoService service = new WhoService(identityRepository, credentialIdentityRepository, List.of());

        Optional<WhoPrincipal> result = service.resolve(credential);
        assertThat(result).isPresent();
        assertThat(result.get().identityId()).isEqualTo(identityId);
        assertThat(result.get().permissions()).isEmpty();
    }

    @Test
    void unionesPermissionsFromAllResolvers() {
        Identity active = Identity.create(identityId, IdentityStatus.ACTIVE);
        when(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId))
                .thenReturn(Optional.of(identityId));
        when(identityRepository.findById(identityId)).thenReturn(Optional.of(active));

        PermissionsResolver resolver1 = identity -> Set.of("read", "write");
        PermissionsResolver resolver2 = identity -> Set.of("write", "delete");

        WhoService service = new WhoService(identityRepository, credentialIdentityRepository,
                List.of(resolver1, resolver2));

        Optional<WhoPrincipal> result = service.resolve(credential);
        assertThat(result).isPresent();
        assertThat(result.get().permissions()).containsExactlyInAnyOrder("read", "write", "delete");
    }
}
