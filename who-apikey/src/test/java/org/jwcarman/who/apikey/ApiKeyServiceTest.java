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
package org.jwcarman.who.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApiKeyServiceTest extends AbstractApiKeyTest {

  @Autowired private ApiKeyService apiKeyService;

  @Autowired private ApiKeyCredentialRepository apiKeyCredentialRepository;

  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  @Autowired private IdentityRepository identityRepository;

  @Autowired private JdbcClient jdbcClient;

  @Test
  void createReturnsKeyWithWhoPrefix() {
    Identity identity = createActiveIdentity();
    String rawKey = apiKeyService.create(identity, "Test Key");

    assertThat(rawKey).startsWith("who_").hasSize(4 + 43);
  }

  @Test
  void keyIsPrefixedSecret() {
    String rawKey = apiKeyService.create(createActiveIdentity(), "Test Key");

    assertThat(rawKey).matches("who_[A-Za-z0-9_-]{43}");
  }

  @Test
  void revokeRemovesCredentialAndLink() {
    String rawKey = apiKeyService.create(createActiveIdentity(), "Test Key");
    String hash = ApiKeyService.sha256Hex(rawKey);
    UUID credentialId = apiKeyCredentialRepository.findByKeyHash(hash).orElseThrow().id();

    apiKeyService.revoke(credentialId);

    assertThat(apiKeyCredentialRepository.findByKeyHash(hash)).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credentialId)).isEmpty();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_api_key_credential WHERE id = ?")
                .param(credentialId)
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_credential_identity WHERE credential_id = ?")
                .param(credentialId)
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void createStoresHashNotRawKey() {
    Identity identity = createActiveIdentity();
    String rawKey = apiKeyService.create(identity, "Test Key");

    // The raw key must NOT be stored as-is
    assertThat(apiKeyCredentialRepository.findByKeyHash(rawKey)).isEmpty();

    // The SHA-256 hash MUST be stored
    String expectedHash = ApiKeyService.sha256Hex(rawKey);
    assertThat(apiKeyCredentialRepository.findByKeyHash(expectedHash)).isPresent();
  }

  @Test
  void createStoresName() {
    Identity identity = createActiveIdentity();
    String rawKey = apiKeyService.create(identity, "Production Server");

    String hash = ApiKeyService.sha256Hex(rawKey);
    ApiKeyCredential credential = apiKeyCredentialRepository.findByKeyHash(hash).orElseThrow();

    assertThat(credential.name()).isEqualTo("Production Server");
  }

  @Test
  void createLinksCredentialToIdentity() {
    Identity identity = createActiveIdentity();
    String rawKey = apiKeyService.create(identity, "Test Key");

    String hash = ApiKeyService.sha256Hex(rawKey);
    ApiKeyCredential credential = apiKeyCredentialRepository.findByKeyHash(hash).orElseThrow();

    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(credential.id()))
        .isPresent()
        .hasValue(identity.id());
  }

  @Test
  void twoCreatesForSameIdentityProduceDifferentKeys() {
    Identity identity = createActiveIdentity();
    String key1 = apiKeyService.create(identity, "Key One");
    String key2 = apiKeyService.create(identity, "Key Two");

    assertThat(key1).isNotEqualTo(key2);

    // Both hashes must be stored
    assertThat(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(key1))).isPresent();
    assertThat(apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(key2))).isPresent();
  }

  private Identity createActiveIdentity() {
    return identityRepository.save(Identity.create());
  }
}
