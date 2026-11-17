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
package org.jwcarman.who.federated;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcFederatedCredentialRepositoryTest extends AbstractFederatedTest {

  @Autowired private JdbcFederatedCredentialRepository repository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  private static final String ISSUER = "https://issuer.example.com";

  @Test
  void savesAndFindsCredentialByIssuerAndSubject() {
    FederatedCredential credential =
        FederatedCredential.create("https://issuer.example.com", "user-123");
    repository.save(credential);

    assertThat(repository.findByIssuerAndSubject("https://issuer.example.com", "user-123"))
        .isPresent()
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(credential.id());
              assertThat(found.issuer()).isEqualTo("https://issuer.example.com");
              assertThat(found.subject()).isEqualTo("user-123");
            });
  }

  @Test
  void returnsEmptyWhenCredentialNotFound() {
    assertThat(repository.findByIssuerAndSubject("https://unknown.example.com", "nobody"))
        .isEmpty();
  }

  @Test
  void deleteByIdRemovesCredential() {
    FederatedCredential credential =
        FederatedCredential.create("https://issuer.example.com", "to-delete");
    repository.save(credential);

    repository.deleteById(credential.id());

    assertThat(repository.findByIssuerAndSubject("https://issuer.example.com", "to-delete"))
        .isEmpty();
  }

  @Test
  void saveIsIdempotentOnConflict() {
    // ON CONFLICT (id) DO NOTHING — saving the same id twice leaves the first record intact
    UUID id = UUID.randomUUID();
    FederatedCredential first =
        new FederatedCredential(id, "https://issuer.example.com", "subject-a");
    FederatedCredential second =
        new FederatedCredential(id, "https://other.example.com", "subject-b");

    repository.save(first);
    repository.save(second); // should be silently ignored

    assertThat(repository.findByIssuerAndSubject("https://issuer.example.com", "subject-a"))
        .isPresent();
    assertThat(repository.findByIssuerAndSubject("https://other.example.com", "subject-b"))
        .isEmpty();
  }

  @Test
  void removeDeletesTheCredentialItOwns() {
    FederatedCredential credential =
        repository.save(FederatedCredential.create(ISSUER, "to-remove"));

    assertThat(repository.remove(credential.id())).isTrue();
    assertThat(repository.findByIssuerAndSubject(ISSUER, "to-remove")).isEmpty();
  }

  @Test
  void removeOfAnotherModulesCredentialDeletesNothing() {
    assertThat(repository.remove(UUID.randomUUID())).isFalse();
  }

  @Test
  void removeUnlinkedDeletesOnlyUnlinkedCredentials() {
    repository.removeUnlinked();
    Identity identity = identityRepository.save(Identity.create());
    FederatedCredential linked = repository.save(FederatedCredential.create(ISSUER, "linked"));
    credentialIdentityRepository.link(linked.id(), identity.id());
    repository.save(FederatedCredential.create(ISSUER, "orphan"));

    assertThat(repository.removeUnlinked()).isOne();
    assertThat(repository.findByIssuerAndSubject(ISSUER, "orphan")).isEmpty();
    assertThat(repository.findByIssuerAndSubject(ISSUER, "linked")).contains(linked);
  }

  @Test
  void findByIdFindsTheCredential() {
    FederatedCredential credential = repository.save(FederatedCredential.create(ISSUER, "by-id"));

    assertThat(repository.findById(credential.id())).contains(credential);
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findDescribesTheCredentialItOwns() {
    FederatedCredential credential =
        repository.save(FederatedCredential.create(ISSUER, "described"));

    assertThat(repository.find(credential.id()))
        .hasValueSatisfying(
            found -> {
              assertThat(found.kind()).isEqualTo(CredentialKind.FEDERATED);
              assertThat(found.label()).contains(ISSUER);
            });
    assertThat(repository.find(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findsAnIdentitysCredentialsOldestFirstAndNoOneElses() {
    Identity alice = identityRepository.save(Identity.create());
    Identity bob = identityRepository.save(Identity.create());
    // time-ordered ids a millisecond apart: ids made back to back may share a millisecond
    FederatedCredential first =
        repository.save(
            new FederatedCredential(
                UUID.fromString("019b0000-0000-7000-8000-000000000001"), ISSUER, "first"));
    FederatedCredential second =
        repository.save(
            new FederatedCredential(
                UUID.fromString("019b0000-0001-7000-8000-000000000001"), ISSUER, "second"));
    FederatedCredential theirs = repository.save(FederatedCredential.create(ISSUER, "theirs"));
    credentialIdentityRepository.link(second.id(), alice.id());
    credentialIdentityRepository.link(first.id(), alice.id());
    credentialIdentityRepository.link(theirs.id(), bob.id());

    assertThat(repository.findAllByIdentityId(alice.id())).containsExactly(first, second);
  }
}
