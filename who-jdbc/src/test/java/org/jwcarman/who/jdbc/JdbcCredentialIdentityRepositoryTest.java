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
package org.jwcarman.who.jdbc;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcCredentialIdentityRepositoryTest extends AbstractJdbcTest {

    @Autowired
    private JdbcIdentityRepository identityRepository;

    @Autowired
    private JdbcCredentialIdentityRepository credentialIdentityRepository;

    @Test
    void linkMapsCredentialToIdentity() {
        Identity identity = savedIdentity();
        UUID credentialId = UUID.randomUUID();

        credentialIdentityRepository.link(credentialId, identity.id());

        Optional<UUID> found = credentialIdentityRepository.findIdentityIdByCredentialId(credentialId);
        assertThat(found).isPresent().contains(identity.id());
    }

    @Test
    void findIdentityIdByCredentialIdReturnsEmptyWhenNotLinked() {
        Optional<UUID> found = credentialIdentityRepository.findIdentityIdByCredentialId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    @Test
    void unlinkRemovesCredentialMapping() {
        Identity identity = savedIdentity();
        UUID credentialId = UUID.randomUUID();
        credentialIdentityRepository.link(credentialId, identity.id());

        credentialIdentityRepository.unlink(credentialId);

        assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId)).isEmpty();
    }

    @Test
    void deletingIdentityCascadesToCredentialMappings() {
        Identity identity = savedIdentity();
        UUID credentialId = UUID.randomUUID();
        credentialIdentityRepository.link(credentialId, identity.id());

        identityRepository.deleteById(identity.id());

        assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId)).isEmpty();
    }

    private Identity savedIdentity() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        return identityRepository.save(identity);
    }
}
