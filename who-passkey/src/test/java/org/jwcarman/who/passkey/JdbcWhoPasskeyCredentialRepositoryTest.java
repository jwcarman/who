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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

class JdbcWhoPasskeyCredentialRepositoryTest extends AbstractPasskeyTest {

  @Autowired private JdbcWhoPasskeyCredentialRepository repository;
  @Autowired private JdbcWhoPasskeyUserRepository userRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private JdbcClient jdbcClient;

  private Identity identity;
  private PublicKeyCredentialUserEntity user;

  @BeforeEach
  void setUp() {
    identity = identityRepository.save(Identity.create());
    user = userRepository.create(identity.id(), "owner@example.com");
  }

  @Test
  void insertLinksTheCredentialToTheIdentity() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Laptop").build();

    CredentialRecords.register(repository, registered);

    UUID credentialUuid = repository.credentialUuid(registered.getCredentialId()).orElseThrow();
    assertThat(credentialUuid.version()).isEqualTo(7);
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialUuid))
        .contains(identity.id());
    assertThat(repository.findCredentialId(credentialUuid)).contains(registered.getCredentialId());
    assertThat(repository.findByCredentialId(registered.getCredentialId()))
        .usingRecursiveComparison()
        .isEqualTo(registered);
    assertThat(repository.findByUserId(user.getId()))
        .singleElement()
        .usingRecursiveComparison()
        .isEqualTo(registered);
  }

  @Test
  void insertForAnUnknownUserHandleFails() {
    ImmutableCredentialRecord orphan =
        CredentialRecords.registration(Bytes.random(), "Laptop").build();

    assertThatThrownBy(() -> CredentialRecords.register(repository, orphan))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(orphan.getUserEntityUserId().toBase64UrlString())
        .hasMessageNotContaining(orphan.getCredentialId().toBase64UrlString());
  }

  @Test
  void signInUpdateKeepsCredentialUuidAndLink() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Laptop").build();
    CredentialRecords.register(repository, registered);
    UUID credentialUuid = repository.credentialUuid(registered.getCredentialId()).orElseThrow();

    Instant later = registered.getCreated().plus(1, ChronoUnit.HOURS);
    CredentialRecord signedIn =
        ImmutableCredentialRecord.fromCredentialRecord(
                repository.findByCredentialId(registered.getCredentialId()))
            .lastUsed(later)
            .signatureCount(5)
            .uvInitialized(false)
            .backupState(true)
            .label("Renamed")
            .backupEligible(false)
            .build();
    repository.save(signedIn);

    CredentialRecord found = repository.findByCredentialId(registered.getCredentialId());
    assertThat(found.getSignatureCount()).isEqualTo(5);
    assertThat(found.getLastUsed()).isEqualTo(later);
    assertThat(found.isUvInitialized()).isFalse();
    assertThat(found.isBackupState()).isTrue();
    assertThat(found.getLabel()).isEqualTo("Laptop");
    assertThat(found.isBackupEligible()).isTrue();
    assertThat(found.getCreated()).isEqualTo(registered.getCreated());
    assertThat(repository.credentialUuid(registered.getCredentialId())).contains(credentialUuid);
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialUuid))
        .contains(identity.id());
  }

  @Test
  void deleteRemovesTheRowAndTheLink() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Laptop").build();
    CredentialRecords.register(repository, registered);
    UUID credentialUuid = repository.credentialUuid(registered.getCredentialId()).orElseThrow();

    repository.delete(registered.getCredentialId());

    assertThat(repository.findByCredentialId(registered.getCredentialId())).isNull();
    assertThat(repository.credentialUuid(registered.getCredentialId())).isEmpty();
    assertThat(repository.findCredentialId(credentialUuid)).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialUuid)).isEmpty();
    repository.delete(registered.getCredentialId());
  }

  @Test
  void signInSaveAfterADeleteDoesNotBringThePasskeyBack() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Laptop").build();
    CredentialRecords.register(repository, registered);
    UUID credentialUuid = repository.credentialUuid(registered.getCredentialId()).orElseThrow();
    // a sign-in read the record, then a revoke committed before the sign-in saved it
    CredentialRecord signedIn =
        ImmutableCredentialRecord.fromCredentialRecord(
                repository.findByCredentialId(registered.getCredentialId()))
            .lastUsed(registered.getCreated().plus(1, ChronoUnit.HOURS))
            .signatureCount(1)
            .build();
    repository.delete(registered.getCredentialId());

    repository.save(signedIn);

    assertThat(repository.findByCredentialId(registered.getCredentialId())).isNull();
    assertThat(repository.credentialUuid(registered.getCredentialId())).isEmpty();
    assertThat(repository.findSummariesByIdentityId(identity.id())).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialUuid)).isEmpty();
  }

  @Test
  void emptyTransportsRoundTrip() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Key").transports(Set.of()).build();

    CredentialRecords.register(repository, registered);

    assertThat(repository.findByCredentialId(registered.getCredentialId()).getTransports())
        .isEmpty();
    String stored =
        jdbcClient
            .sql(
                "SELECT authenticator_transports FROM who_passkey_credential"
                    + " WHERE credential_id = :id")
            .param("id", registered.getCredentialId().toBase64UrlString())
            .query(String.class)
            .optional()
            .orElse(null);
    assertThat(stored).isNull();
  }

  @Test
  void nullLastUsedReadsBack() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Key").lastUsed(null).build();

    CredentialRecords.register(repository, registered);

    assertThat(repository.findByCredentialId(registered.getCredentialId()).getLastUsed()).isNull();
  }

  @Test
  void deletingTheIdentityCascades() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Laptop").build();
    CredentialRecords.register(repository, registered);
    UUID credentialUuid = repository.credentialUuid(registered.getCredentialId()).orElseThrow();

    identityRepository.deleteById(identity.id());

    assertThat(userRepository.findById(user.getId())).isNull();
    assertThat(repository.findByCredentialId(registered.getCredentialId())).isNull();
    assertThat(repository.findByUserId(user.getId())).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialUuid)).isEmpty();
  }

  @Test
  void unknownIdsFindNothing() {
    assertThat(repository.findByCredentialId(Bytes.random())).isNull();
    assertThat(repository.findByUserId(Bytes.random())).isEmpty();
    assertThat(repository.credentialUuid(Bytes.random())).isEmpty();
    assertThat(repository.findCredentialId(UUID.randomUUID())).isEmpty();
  }
}
