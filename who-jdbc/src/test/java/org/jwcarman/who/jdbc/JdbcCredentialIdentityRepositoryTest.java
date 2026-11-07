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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class JdbcCredentialIdentityRepositoryTest extends AbstractJdbcTest {

  @Autowired private JdbcIdentityRepository identityRepository;

  @Autowired private JdbcCredentialIdentityRepository credentialIdentityRepository;

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
    Optional<UUID> found =
        credentialIdentityRepository.findIdentityIdByCredentialId(UUID.randomUUID());
    assertThat(found).isEmpty();
  }

  @Test
  void findCredentialIdsByIdentityIdReturnsOnlyThatIdentitysCredentials() {
    Identity identity = savedIdentity();
    Identity other = savedIdentity();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    credentialIdentityRepository.link(first, identity.id());
    credentialIdentityRepository.link(second, identity.id());
    credentialIdentityRepository.link(UUID.randomUUID(), other.id());

    assertThat(credentialIdentityRepository.findCredentialIdsByIdentityId(identity.id()))
        .containsExactlyInAnyOrder(first, second);
  }

  @Test
  void findCredentialIdsByIdentityIdIsEmptyWithoutLinks() {
    assertThat(credentialIdentityRepository.findCredentialIdsByIdentityId(UUID.randomUUID()))
        .isEmpty();
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

  @Test
  void duplicateKeyIsStillTranslated() {
    UUID identityId = savedIdentity().id();
    UUID credentialId = UUID.randomUUID();
    credentialIdentityRepository.link(credentialId, identityId);

    assertThatThrownBy(() -> credentialIdentityRepository.link(credentialId, identityId))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private Identity savedIdentity() {
    Identity identity = Identity.create();
    return identityRepository.save(identity);
  }
}
