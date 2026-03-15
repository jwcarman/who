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
package org.jwcarman.who.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcJwtCredentialRepositoryTest extends AbstractJwtTest {

  @Autowired private JdbcJwtCredentialRepository repository;

  @Test
  void savesAndFindsCredentialByIssuerAndSubject() {
    JwtCredential credential = JwtCredential.create("https://issuer.example.com", "user-123");
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
    JwtCredential credential = JwtCredential.create("https://issuer.example.com", "to-delete");
    repository.save(credential);

    repository.deleteById(credential.id());

    assertThat(repository.findByIssuerAndSubject("https://issuer.example.com", "to-delete"))
        .isEmpty();
  }

  @Test
  void saveIsIdempotentOnConflict() {
    // ON CONFLICT (id) DO NOTHING — saving the same id twice leaves the first record intact
    UUID id = UUID.randomUUID();
    JwtCredential first = new JwtCredential(id, "https://issuer.example.com", "subject-a");
    JwtCredential second = new JwtCredential(id, "https://other.example.com", "subject-b");

    repository.save(first);
    repository.save(second); // should be silently ignored

    assertThat(repository.findByIssuerAndSubject("https://issuer.example.com", "subject-a"))
        .isPresent();
    assertThat(repository.findByIssuerAndSubject("https://other.example.com", "subject-b"))
        .isEmpty();
  }
}
