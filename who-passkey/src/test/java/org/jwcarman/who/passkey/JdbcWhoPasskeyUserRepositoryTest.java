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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

class JdbcWhoPasskeyUserRepositoryTest extends AbstractPasskeyTest {

  @Autowired private JdbcWhoPasskeyUserRepository repository;
  @Autowired private IdentityRepository identityRepository;

  @Test
  void createMakesA64ByteHandleOncePerIdentity() {
    Identity identity = identityRepository.save(Identity.create());

    PublicKeyCredentialUserEntity first = repository.create(identity.id(), "owner@example.com");
    PublicKeyCredentialUserEntity second = repository.create(identity.id(), "other@example.com");

    assertThat(first.getId().getBytes()).hasSize(64);
    assertThat(first.getName()).isEqualTo("owner@example.com");
    assertThat(first.getDisplayName()).isEqualTo("owner@example.com");
    assertThat(second).isEqualTo(first);
    assertThat(repository.findById(first.getId())).isEqualTo(first);
    assertThat(repository.findIdentityId(first.getId())).contains(identity.id());

    Identity other = identityRepository.save(Identity.create());
    assertThat(repository.create(other.id(), "owner@example.com").getId())
        .isNotEqualTo(first.getId());
  }

  @Test
  void findByUsernameUsesTheIdentityId() {
    Identity identity = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity entity = repository.create(identity.id(), "owner@example.com");

    assertThat(repository.findByUsername(identity.id().toString())).isEqualTo(entity);
    assertThat(repository.findByUsername("owner@example.com")).isNull();
    assertThat(repository.findByUsername(UUID.randomUUID().toString())).isNull();
  }

  @Test
  void unknownHandlesFindNothing() {
    Bytes unknown = Bytes.random();

    assertThat(repository.findById(unknown)).isNull();
    assertThat(repository.findIdentityId(unknown)).isEqualTo(Optional.empty());
  }

  @Test
  void saveUpdatesAnExistingEntityAndInsertsForAnIdentityName() {
    Identity identity = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity entity = repository.create(identity.id(), "old@example.com");

    PublicKeyCredentialUserEntity renamed =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(entity.getId())
            .name("new@example.com")
            .displayName("New Name")
            .build();
    repository.save(renamed);
    assertThat(repository.findById(entity.getId())).isEqualTo(renamed);

    Identity springMade = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity spring =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(Bytes.random())
            .name(springMade.id().toString())
            .displayName(springMade.id().toString())
            .build();
    repository.save(spring);
    assertThat(repository.findByUsername(springMade.id().toString())).isEqualTo(spring);
  }

  @Test
  void deleteRemovesTheEntity() {
    Identity identity = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity entity = repository.create(identity.id(), "owner@example.com");

    repository.delete(entity.getId());

    assertThat(repository.findById(entity.getId())).isNull();
    assertThat(repository.findByUsername(identity.id().toString())).isNull();
  }
}
