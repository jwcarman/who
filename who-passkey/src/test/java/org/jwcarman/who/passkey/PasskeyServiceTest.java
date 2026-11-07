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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

class PasskeyServiceTest extends AbstractPasskeyTest {

  @Autowired private PasskeyService service;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private JdbcWhoPasskeyUserRepository userRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  private Identity identity;
  private PublicKeyCredentialUserEntity user;

  @BeforeEach
  void setUp() {
    identity = identityRepository.save(Identity.create());
    user = userRepository.create(identity.id(), "owner@example.com");
  }

  @Test
  void revokeUsesTheCredentialUuid() {
    ImmutableCredentialRecord registered =
        CredentialRecords.registration(user.getId(), "Laptop").build();
    credentialRepository.save(registered);
    UUID credentialUuid =
        credentialRepository.credentialUuid(registered.getCredentialId()).orElseThrow();

    service.revoke(credentialUuid);

    assertThat(credentialRepository.findByCredentialId(registered.getCredentialId())).isNull();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialUuid)).isEmpty();
    service.revoke(UUID.randomUUID());
  }

  @Test
  void listReturnsLabelsAndTimes() {
    Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(2, ChronoUnit.DAYS);
    Instant lastUsed = created.plus(1, ChronoUnit.DAYS);
    ImmutableCredentialRecord laptop =
        CredentialRecords.registration(user.getId(), "Laptop")
            .created(created)
            .lastUsed(lastUsed)
            .build();
    ImmutableCredentialRecord phone =
        CredentialRecords.registration(user.getId(), "Phone")
            .created(created.plus(1, ChronoUnit.HOURS))
            .lastUsed(created.plus(1, ChronoUnit.HOURS))
            .build();
    credentialRepository.save(phone);
    credentialRepository.save(laptop);
    Identity other = identityRepository.save(Identity.create());
    credentialRepository.save(
        CredentialRecords.registration(
                userRepository.create(other.id(), "other@example.com").getId(), "Other")
            .build());

    UUID laptopUuid = credentialRepository.credentialUuid(laptop.getCredentialId()).orElseThrow();
    UUID phoneUuid = credentialRepository.credentialUuid(phone.getCredentialId()).orElseThrow();
    assertThat(service.list(identity.id()))
        .containsExactly(
            new PasskeySummary(laptopUuid, "Laptop", created, lastUsed),
            new PasskeySummary(
                phoneUuid,
                "Phone",
                created.plus(1, ChronoUnit.HOURS),
                created.plus(1, ChronoUnit.HOURS)));
    assertThat(service.list(UUID.randomUUID())).isEmpty();
  }

  @Test
  void passkeyCredentialIsACredential() {
    UUID id = UUID.randomUUID();
    assertThat(new PasskeyCredential(id).id()).isEqualTo(id);
  }
}
