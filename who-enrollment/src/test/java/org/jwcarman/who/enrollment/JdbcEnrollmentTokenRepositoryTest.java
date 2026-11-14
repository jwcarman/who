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
package org.jwcarman.who.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcEnrollmentTokenRepositoryTest extends AbstractEnrollmentTest {

  @Autowired private EnrollmentTokenRepository repository;

  @Autowired private IdentityRepository identityRepository;

  @Autowired private JdbcClient jdbcClient;

  private Identity identity;

  @BeforeEach
  void setUp() {
    identity = identityRepository.save(Identity.create());
  }

  @Test
  void savesAndRetrievesById() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(24));

    assertThat(repository.save(token)).isSameAs(token);
    assertThat(repository.findById(token.id()))
        .isPresent()
        .get()
        .satisfies(
            found -> {
              assertThat(found.id()).isEqualTo(token.id());
              assertThat(found.identityId()).isEqualTo(identity.id());
              assertThat(found.status()).isEqualTo(EnrollmentTokenStatus.PENDING);
            });
  }

  @Test
  void findsByValue() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(24));
    repository.save(token);

    assertThat(repository.findByValue(token.value()))
        .isPresent()
        .get()
        .satisfies(found -> assertThat(found.id()).isEqualTo(token.id()));
  }

  @Test
  void findByValueReturnsEmptyForUnknownValue() {
    assertThat(repository.findByValue("nonexistent-value")).isEmpty();
  }

  @Test
  void upsertUpdatesStatusOnConflict() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(24));
    repository.save(token);

    EnrollmentToken redeemed = token.redeem();
    repository.save(redeemed);

    assertThat(repository.findById(token.id()))
        .isPresent()
        .get()
        .satisfies(found -> assertThat(found.status()).isEqualTo(EnrollmentTokenStatus.REDEEMED));
  }

  @Test
  void deleteByIdRemovesToken() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(24));
    repository.save(token);

    repository.deleteById(token.id());

    assertThat(repository.findById(token.id())).isEmpty();
  }

  @Test
  void deletingIdentityCascadesToTokens() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(24));
    repository.save(token);

    identityRepository.deleteById(identity.id());

    assertThat(repository.findById(token.id())).isEmpty();
  }

  @Test
  void redeemIsTrueOnceThenFalse() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(24));
    repository.save(token);

    assertThat(repository.redeem(token.id(), Instant.now())).isTrue();
    assertThat(repository.redeem(token.id(), Instant.now())).isFalse();
    assertThat(repository.findById(token.id()))
        .get()
        .satisfies(
            found -> {
              assertThat(found.status()).isEqualTo(EnrollmentTokenStatus.REDEEMED);
              assertThat(found.redeemedAt()).isNotNull();
            });
  }

  @Test
  void redeemIsFalseWhenExpired() {
    EnrollmentToken token = EnrollmentToken.create(identity, Duration.ofHours(-1));
    repository.save(token);

    assertThat(repository.redeem(token.id(), Instant.now())).isFalse();
    assertThat(repository.findById(token.id()))
        .get()
        .satisfies(found -> assertThat(found.status()).isEqualTo(EnrollmentTokenStatus.PENDING));
  }

  @Test
  void revokeAllPendingForIdentityRevokesOnlyThatIdentitysPendingTokens() {
    Identity other = identityRepository.save(Identity.create());
    EnrollmentToken pending =
        repository.save(EnrollmentToken.create(identity, Duration.ofHours(24)));
    EnrollmentToken redeemed =
        repository.save(EnrollmentToken.create(identity, Duration.ofHours(24)));
    repository.redeem(redeemed.id(), Instant.now());
    EnrollmentToken othersPending =
        repository.save(EnrollmentToken.create(other, Duration.ofHours(24)));

    repository.revokeAllPendingForIdentity(identity.id());

    assertThat(repository.findById(pending.id()))
        .get()
        .extracting(EnrollmentToken::status)
        .isEqualTo(EnrollmentTokenStatus.REVOKED);
    assertThat(repository.findById(redeemed.id()))
        .get()
        .extracting(EnrollmentToken::status)
        .isEqualTo(EnrollmentTokenStatus.REDEEMED);
    assertThat(repository.findById(othersPending.id()))
        .get()
        .extracting(EnrollmentToken::status)
        .isEqualTo(EnrollmentTokenStatus.PENDING);
  }

  @Test
  void revokeOnlyAffectsPending() {
    EnrollmentToken pending = EnrollmentToken.create(identity, Duration.ofHours(24));
    EnrollmentToken redeemed = EnrollmentToken.create(identity, Duration.ofHours(24));
    repository.save(pending);
    repository.save(redeemed);
    repository.redeem(redeemed.id(), Instant.now());

    assertThat(repository.revoke(pending.id())).isTrue();
    assertThat(repository.revoke(redeemed.id())).isFalse();
    assertThat(repository.revoke(pending.id())).isFalse();

    assertThat(repository.findById(pending.id()))
        .get()
        .satisfies(found -> assertThat(found.status()).isEqualTo(EnrollmentTokenStatus.REVOKED));
    assertThat(repository.findById(redeemed.id()))
        .get()
        .satisfies(found -> assertThat(found.status()).isEqualTo(EnrollmentTokenStatus.REDEEMED));
  }

  @Test
  void timestampColumnsAreTimestamptz() {
    List<String> types =
        jdbcClient
            .sql(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'who_enrollment_token' AND column_name IN ('created_at', 'expires_at', 'redeemed_at')")
            .query(String.class)
            .list();

    assertThat(types).hasSize(3).containsOnly("timestamp with time zone");
  }

  @Test
  void instantsRoundTripRegardlessOfJvmZone() {
    TimeZone original = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    try {
      Instant instant = Instant.parse("2026-01-15T12:34:56.123456Z");
      EnrollmentToken token =
          new EnrollmentToken(
              UUID.randomUUID(),
              identity.id(),
              "round-trip-" + UUID.randomUUID(),
              EnrollmentTokenStatus.REDEEMED,
              instant,
              instant,
              instant);
      repository.save(token);

      EnrollmentToken found = repository.findById(token.id()).orElseThrow();

      assertThat(found.createdAt()).isEqualTo(instant);
      assertThat(found.expiresAt()).isEqualTo(instant);
      assertThat(found.redeemedAt()).isEqualTo(instant);
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
