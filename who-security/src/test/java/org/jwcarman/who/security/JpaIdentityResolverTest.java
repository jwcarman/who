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
package org.jwcarman.who.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.ExternalIdentity;
import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.repository.ExternalIdentityRepository;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaIdentityResolverTest {

    @Mock
    private ExternalIdentityRepository repository;

    @Mock
    private UserProvisioningPolicy provisioningPolicy;

    @InjectMocks
    private DefaultIdentityResolver resolver;

    @Test
    void shouldResolveExistingIdentity() {
        String issuer = "https://auth.example.com";
        String subject = "user123";
        UUID userId = UUID.randomUUID();

        ExternalIdentity identity = ExternalIdentity.create(UUID.randomUUID(), userId, issuer, subject);

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.of(identity));

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isEqualTo(userId);
    }

    @Test
    void shouldDelegateToProvisioningPolicyForUnknownIdentity() {
        String issuer = "https://auth.example.com";
        String subject = "newuser";
        UUID newUserId = UUID.randomUUID();

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());
        when(provisioningPolicy.handleUnknownIdentity(any(ExternalIdentityKey.class)))
            .thenReturn(newUserId);

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isEqualTo(newUserId);
    }

    @Test
    void shouldReturnNullWhenProvisioningPolicyDenies() {
        String issuer = "https://auth.example.com";
        String subject = "denied";

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());
        when(provisioningPolicy.handleUnknownIdentity(any(ExternalIdentityKey.class)))
            .thenReturn(null);

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isNull();
    }
}
