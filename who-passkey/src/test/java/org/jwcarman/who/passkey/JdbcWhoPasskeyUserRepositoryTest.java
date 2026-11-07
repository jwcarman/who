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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

class JdbcWhoPasskeyUserRepositoryTest extends AbstractPasskeyTest {

  @Autowired private JdbcWhoPasskeyUserRepository repository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

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
    assertThat(repository.findIdentityId(unknown)).isEmpty();
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
  void renameChangesTheNamesOfOneIdentityOnly() {
    Identity identity = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity entity =
        repository.create(identity.id(), identity.id().toString());
    Identity other = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity othersEntity = repository.create(other.id(), "other@example.com");

    repository.rename(identity.id(), "owner@example.com");
    repository.rename(UUID.randomUUID(), "nobody@example.com");

    PublicKeyCredentialUserEntity renamed = repository.findById(entity.getId());
    assertThat(renamed.getName()).isEqualTo("owner@example.com");
    assertThat(renamed.getDisplayName()).isEqualTo("owner@example.com");
    assertThat(repository.findIdentityId(entity.getId())).contains(identity.id());
    assertThat(repository.findById(othersEntity.getId()).getName()).isEqualTo("other@example.com");
  }

  @Test
  void deleteRemovesTheEntity() {
    Identity identity = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity entity = repository.create(identity.id(), "owner@example.com");

    repository.delete(entity.getId());

    assertThat(repository.findById(entity.getId())).isNull();
    assertThat(repository.findByUsername(identity.id().toString())).isNull();
  }

  @Test
  void aMaximumLengthNameRoundTrips() {
    Identity identity = identityRepository.save(Identity.create());
    String name = "a".repeat(64) + "@" + "b".repeat(251) + ".com";

    PublicKeyCredentialUserEntity entity = repository.create(identity.id(), name);

    assertThat(name).hasSize(320);
    assertThat(repository.findById(entity.getId()).getName()).isEqualTo(name);
    assertThat(repository.findById(entity.getId()).getDisplayName()).isEqualTo(name);
  }

  @Test
  void deletingTheUserEntityUnlinksItsPasskeys() {
    Identity identity = identityRepository.save(Identity.create());
    PublicKeyCredentialUserEntity entity = repository.create(identity.id(), "owner@example.com");
    ImmutableCredentialRecord laptop =
        CredentialRecords.registration(entity.getId(), "Laptop").build();
    ImmutableCredentialRecord phone =
        CredentialRecords.registration(entity.getId(), "Phone").build();
    CredentialRecords.register(credentialRepository, laptop);
    CredentialRecords.register(credentialRepository, phone);
    UUID laptopUuid = credentialRepository.credentialUuid(laptop.getCredentialId()).orElseThrow();
    UUID phoneUuid = credentialRepository.credentialUuid(phone.getCredentialId()).orElseThrow();

    repository.delete(entity.getId());

    assertThat(credentialRepository.findByUserId(entity.getId())).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(laptopUuid)).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(phoneUuid)).isEmpty();
  }

  @Test
  void createTwiceInOneTransactionKeepsTheTransactionUsable() {
    Identity identity = identityRepository.save(Identity.create());

    PublicKeyCredentialUserEntity first = repository.create(identity.id(), "owner@example.com");
    PublicKeyCredentialUserEntity second = repository.create(identity.id(), "owner@example.com");

    assertThat(second).isEqualTo(first);
    assertThat(repository.findByUsername(identity.id().toString())).isEqualTo(first);
  }

  @Test
  void saveOfANewEntityNeedsAnIdentityIdName() {
    PublicKeyCredentialUserEntity unnamed =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(Bytes.random())
            .name("someone@example.com")
            .displayName("someone@example.com")
            .build();

    assertThatThrownBy(() -> repository.save(unnamed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("someone@example.com")
        .hasMessageNotContaining(unnamed.getId().toBase64UrlString());
  }
}
