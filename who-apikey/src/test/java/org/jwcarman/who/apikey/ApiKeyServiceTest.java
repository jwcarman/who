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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyServiceTest extends AbstractApiKeyTest {

  private static final Duration THIRTY_DAYS = Duration.ofDays(30);
  private static final Set<String> READ = Set.of("task.read");

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ApiKeyCredentialRepository apiKeyCredentialRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private JdbcClient jdbcClient;

  private Identity identity() {
    return identityRepository.save(Identity.create());
  }

  private ApiKeyCredential stored(NewApiKey key) {
    return apiKeyCredentialRepository.findById(key.id()).orElseThrow();
  }

  @Test
  void create_answers_id_name_key_scopes_and_expiry() {
    Instant before = Instant.now();

    NewApiKey key =
        apiKeyService.create(
            identity(), "Build server", THIRTY_DAYS, Set.of("task.read", "task.write"));

    assertThat(key.name()).isEqualTo("Build server");
    assertThat(key.key()).startsWith("who_");
    assertThat(key.scopes()).containsExactlyInAnyOrder("task.read", "task.write");
    assertThat(key.expiresAt())
        .isBetween(before.plus(THIRTY_DAYS), Instant.now().plus(THIRTY_DAYS));
    ApiKeyCredential credential =
        apiKeyCredentialRepository.findByKeyHash(ApiKeyService.sha256Hex(key.key())).orElseThrow();
    assertThat(credential.id()).isEqualTo(key.id());
    assertThat(credential.name()).isEqualTo("Build server");
    assertThat(credential.scopes()).isEqualTo(key.scopes());
    assertThat(credential.lastUsedAt()).isNull();
  }

  @Test
  void create_stores_the_hash_not_the_key() {
    NewApiKey key = apiKeyService.create(identity(), "Hashed", THIRTY_DAYS, READ);

    assertThat(apiKeyCredentialRepository.findByKeyHash(key.key())).isEmpty();
    assertThat(stored(key).keyHash()).isEqualTo(ApiKeyService.sha256Hex(key.key()));
  }

  @Test
  void create_links_the_key_to_its_identity() {
    Identity owner = identity();

    NewApiKey key = apiKeyService.create(owner, "Linked", THIRTY_DAYS, READ);

    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(key.id()))
        .hasValue(owner.id());
  }

  @Test
  void two_creates_make_different_keys() {
    Identity owner = identity();

    NewApiKey one = apiKeyService.create(owner, "One", THIRTY_DAYS, READ);
    NewApiKey two = apiKeyService.create(owner, "Two", THIRTY_DAYS, READ);

    assertThat(one.key()).isNotEqualTo(two.key());
    assertThat(one.id()).isNotEqualTo(two.id());
  }

  @Test
  void a_lifetime_of_exactly_the_maximum_is_allowed() {
    NewApiKey key = apiKeyService.create(identity(), "Year", Duration.ofDays(365), READ);

    assertThat(stored(key).expiresAt())
        .isEqualTo(stored(key).createdAt().plus(Duration.ofDays(365)));
  }

  @Test
  void find_by_identity_lists_only_that_identitys_keys_by_name() {
    Identity owner = identity();
    apiKeyService.create(owner, "Zeta", THIRTY_DAYS, READ);
    apiKeyService.create(owner, "Alpha", THIRTY_DAYS, Set.of("task.write"));
    apiKeyService.create(identity(), "Someone else's", THIRTY_DAYS, READ);
    credentialIdentityRepository.link(UUID.randomUUID(), owner.id());

    assertThat(apiKeyService.findByIdentity(owner.id()))
        .extracting(ApiKey::name)
        .containsExactly("Alpha", "Zeta");
    assertThat(apiKeyService.findByIdentity(owner.id()).getFirst().scopes())
        .containsExactly("task.write");
  }

  @Test
  void find_by_identity_marks_an_expired_key() {
    Identity owner = identity();
    NewApiKey key = apiKeyService.create(owner, "Old", THIRTY_DAYS, READ);
    ApiKeyCredential credential = stored(key);
    Instant past = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    apiKeyCredentialRepository.save(
        new ApiKeyCredential(
            credential.id(),
            credential.name(),
            credential.keyHash(),
            credential.scopes(),
            past.minus(THIRTY_DAYS),
            past,
            null));

    assertThat(apiKeyService.findByIdentity(owner.id()))
        .singleElement()
        .satisfies(
            found -> {
              assertThat(found.expired()).isTrue();
              assertThat(found.expiresAt()).isEqualTo(past);
            });
  }

  @Test
  void rotate_copies_name_scopes_and_lifetime_and_expires_the_old_key_after_the_overlap() {
    Identity owner = identity();
    NewApiKey old = apiKeyService.create(owner, "Deploys", THIRTY_DAYS, READ);
    Duration overlap = Duration.ofDays(2);
    Instant before = Instant.now();

    NewApiKey rotated = apiKeyService.rotate(old.id(), overlap);

    assertThat(rotated.id()).isNotEqualTo(old.id());
    assertThat(rotated.key()).isNotEqualTo(old.key());
    assertThat(rotated.name()).isEqualTo("Deploys");
    assertThat(rotated.scopes()).isEqualTo(READ);
    assertThat(stored(rotated).expiresAt())
        .isEqualTo(stored(rotated).createdAt().plus(THIRTY_DAYS));
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(rotated.id()))
        .hasValue(owner.id());
    assertThat(stored(old).expiresAt())
        .isBetween(before.plus(overlap).minusSeconds(1), Instant.now().plus(overlap));
  }

  @Test
  void rotate_keeps_an_earlier_expiry() {
    NewApiKey old = apiKeyService.create(identity(), "Soon", Duration.ofDays(1), READ);
    Instant ownExpiry = stored(old).expiresAt();

    apiKeyService.rotate(old.id(), Duration.ofDays(7));

    assertThat(stored(old).expiresAt()).isEqualTo(ownExpiry);
  }

  @Test
  void rotate_refuses_an_overlap_over_seven_days() {
    NewApiKey old = apiKeyService.create(identity(), "Long", THIRTY_DAYS, READ);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> apiKeyService.rotate(old.id(), Duration.ofDays(7).plusSeconds(1)))
        .withMessageContaining("overlap");
  }

  @Test
  void rotate_refuses_a_gone_key() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> apiKeyService.rotate(UUID.randomUUID(), Duration.ofDays(1)));
  }

  @Test
  void rotate_refuses_an_expired_key() {
    NewApiKey old = apiKeyService.create(identity(), "Done", THIRTY_DAYS, READ);
    apiKeyService.rotate(old.id(), Duration.ZERO);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> apiKeyService.rotate(old.id(), Duration.ofDays(1)))
        .withMessageContaining("expired");
  }

  @Test
  void revoke_removes_the_key_its_scopes_and_its_link() {
    NewApiKey key = apiKeyService.create(identity(), "Gone", THIRTY_DAYS, READ);

    apiKeyService.revoke(key.id());

    assertThat(apiKeyCredentialRepository.findById(key.id())).isEmpty();
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(key.id())).isEmpty();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_api_key_scope WHERE credential_id = ?")
                .param(key.id())
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void revoke_leaves_a_linked_non_api_key_credential_alone() {
    Identity owner = identity();
    UUID otherCredentialId = UUID.randomUUID();
    credentialIdentityRepository.link(otherCredentialId, owner.id());

    apiKeyService.revoke(otherCredentialId);

    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(otherCredentialId))
        .hasValue(owner.id());
  }
}
