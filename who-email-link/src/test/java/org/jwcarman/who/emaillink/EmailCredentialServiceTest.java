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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class EmailCredentialServiceTest extends AbstractEmailLinkTest {

  @Autowired private EmailCredentialService service;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  @Test
  void registerLinksTheCredentialToTheIdentity() {
    Identity identity = identityRepository.save(Identity.create());

    EmailCredential credential = service.register(identity, "Owner@Example.com");

    assertThat(credential.address()).isEqualTo("owner@example.com");
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
        .contains(identity.id());
  }

  @Test
  void registeringATakenAddressThrows() {
    Identity identity = identityRepository.save(Identity.create());
    service.register(identity, "taken@example.com");
    Identity other = identityRepository.save(Identity.create());

    assertThatThrownBy(() -> service.register(other, "taken@example.com"))
        .isInstanceOf(EmailAddressTakenException.class)
        .hasMessageNotContaining("taken@example.com");
    assertThatThrownBy(() -> service.register(other, "TAKEN@Example.com"))
        .isInstanceOf(EmailAddressTakenException.class);
  }

  @Test
  void findByAddressNormalizes() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential credential = service.register(identity, "find@example.com");

    assertThat(service.findByAddress("  FIND@example.com ")).contains(credential);
  }

  @Test
  void revokeDeletesTheCredentialAndItsLink() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential credential = service.register(identity, "gone@example.com");

    service.revoke(credential.id());

    assertThat(service.findByAddress("gone@example.com")).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
        .isEmpty();
  }

  @Autowired private EmailCredentialRepository emailCredentialRepository;

  @Test
  void aConcurrentRegistrationLosingTheRaceIsReportedAsTaken() {
    Identity identity = identityRepository.save(Identity.create());
    service.register(identity, "race@example.com");
    // The pre-check misses the winner's row, as it would if the winner committed a moment later;
    // the real insert then hits the unique constraint.
    EmailCredentialRepository blind = mock(EmailCredentialRepository.class);
    when(blind.findByAddress(anyString())).thenReturn(Optional.empty());
    when(blind.save(any())).thenAnswer(call -> emailCredentialRepository.save(call.getArgument(0)));
    EmailCredentialService racing = new EmailCredentialService(blind, credentialIdentityRepository);
    Identity other = identityRepository.save(Identity.create());

    assertThatThrownBy(() -> racing.register(other, "race@example.com"))
        .isInstanceOf(EmailAddressTakenException.class);
  }

  @Test
  void revokingAnUnknownCredentialDoesNothing() {
    CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
    EmailCredentialService isolated = new EmailCredentialService(emailCredentialRepository, links);

    isolated.revoke(UUID.randomUUID());

    verifyNoInteractions(links);
  }

  @Test
  void findByIdFindsTheCredential() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential credential = service.register(identity, "byid@example.com");

    assertThat(service.findById(credential.id())).contains(credential);
    assertThat(service.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findOldestForReturnsTheIdentitysCredentialWithTheSmallestId() {
    Identity identity = identityRepository.save(Identity.create());
    Identity other = identityRepository.save(Identity.create());
    EmailCredential first = service.register(identity, "first@example.com");
    service.register(other, "other@example.com");
    EmailCredential second = service.register(identity, "second@example.com");
    // PostgreSQL orders UUIDs by unsigned bytes, which is the order of their hex strings.
    EmailCredential oldest =
        Stream.of(first, second).min(Comparator.comparing(c -> c.id().toString())).orElseThrow();

    assertThat(service.findOldestFor(identity.id())).contains(oldest);
  }

  @Test
  void findOldestForSkipsRevokedCredentials() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential first = service.register(identity, "revoked@example.com");
    EmailCredential second = service.register(identity, "kept@example.com");

    service.revoke(first.id());

    assertThat(service.findOldestFor(identity.id())).contains(second);
  }

  @Test
  void findOldestForIsEmptyWithoutEmailCredentials() {
    Identity identity = identityRepository.save(Identity.create());

    assertThat(service.findOldestFor(identity.id())).isEmpty();
  }

  @Autowired private JdbcClient jdbcClient;

  @Autowired private EmailLinkTokenRepository tokenRepository;

  @Test
  void registerReusesAnOrphanedAddress() {
    Identity deleted = identityRepository.save(Identity.create());
    EmailCredential orphan = service.register(deleted, "orphan@example.com");
    deleteIdentityRow(deleted);
    Identity identity = identityRepository.save(Identity.create());

    EmailCredential credential = service.register(identity, "Orphan@Example.com");

    assertThat(credential.address()).isEqualTo("orphan@example.com");
    assertThat(credential.id()).isNotEqualTo(orphan.id());
    assertThat(service.findByAddress("orphan@example.com")).contains(credential);
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
        .contains(identity.id());
  }

  @Test
  void aLinkSentToTheDeletedIdentityDoesNotSignInTheNewOne() {
    Identity deleted = identityRepository.save(Identity.create());
    EmailCredential orphan = service.register(deleted, "stale-link@example.com");
    Instant now = Instant.now();
    tokenRepository.issue(
        orphan.id(), "stale-token-hash", now, now.plus(Duration.ofMinutes(15)), Duration.ZERO);
    deleteIdentityRow(deleted);
    Identity identity = identityRepository.save(Identity.create());

    service.register(identity, "stale-link@example.com");

    assertThat(tokenRepository.consume("stale-token-hash", now)).isEmpty();
  }

  private void deleteIdentityRow(Identity identity) {
    // A raw delete leaves the credential row (and its link token) behind; only its link cascades.
    jdbcClient.sql("DELETE FROM who_identity WHERE id = :id").param("id", identity.id()).update();
  }

  @Test
  void registerOfAnAddressLinkedElsewhereStillThrows() {
    Identity owner = identityRepository.save(Identity.create());
    EmailCredential owned = service.register(owner, "owned@example.com");
    Identity other = identityRepository.save(Identity.create());

    assertThatThrownBy(() -> service.register(other, "owned@example.com"))
        .isInstanceOf(EmailAddressTakenException.class);
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(owned.id()))
        .contains(owner.id());
  }

  @Test
  void registerOfAnAddressTheIdentityAlreadyHasReturnsIt() {
    Identity identity = identityRepository.save(Identity.create());
    EmailCredential registered = service.register(identity, "again@example.com");

    assertThat(service.register(identity, "again@example.com")).isEqualTo(registered);
  }

  @Test
  void aConcurrentClaimOfAnOrphanLosingTheRaceIsReportedAsTaken() {
    // The winner already replaced the orphan with its own credential; this registration still sees
    // the orphan it looked up a moment earlier, which is gone by the time it deletes it.
    emailCredentialRepository.save(EmailCredential.create("claim@example.com"));
    EmailCredential stale = EmailCredential.create("claim@example.com");
    EmailCredentialRepository behind = mock(EmailCredentialRepository.class);
    when(behind.findByAddress("claim@example.com")).thenReturn(Optional.of(stale));
    when(behind.save(any()))
        .thenAnswer(call -> emailCredentialRepository.save(call.getArgument(0)));
    EmailCredentialService racing =
        new EmailCredentialService(behind, credentialIdentityRepository);
    Identity identity = identityRepository.save(Identity.create());

    assertThatThrownBy(() -> racing.register(identity, "claim@example.com"))
        .isInstanceOf(EmailAddressTakenException.class);
  }
}
