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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class WhoEnrollmentServiceTest extends AbstractEnrollmentTest {

  @Autowired private WhoEnrollmentService service;

  @Autowired private IdentityRepository identityRepository;

  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  @Autowired private EnrollmentTokenRepository tokenRepository;

  @Autowired private JdbcClient jdbcClient;

  private Identity savedIdentity() {
    Identity identity = Identity.create();
    return identityRepository.save(identity);
  }

  private Credential credential() {
    UUID id = UUID.randomUUID();
    return () -> id;
  }

  @Test
  void createTokenThrowsForUnknownIdentity() {
    Identity unknownIdentity = Identity.create();
    assertThatThrownBy(() -> service.createToken(unknownIdentity))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createTokenReturnsPendingToken() {
    Identity identity = savedIdentity();

    EnrollmentToken token = service.createToken(identity);

    assertThat(token.status()).isEqualTo(EnrollmentTokenStatus.PENDING);
    assertThat(token.identityId()).isEqualTo(identity.id());
    assertThat(token.isPending()).isTrue();
  }

  @Test
  void createTokenUsesConfiguredExpiration() {
    Identity identity = savedIdentity();
    Instant before = Instant.now();

    EnrollmentToken token = service.createToken(identity);

    // default expiration is 24 hours
    assertThat(token.expiresAt())
        .isAfter(before.plusSeconds(23 * 3600))
        .isBefore(before.plusSeconds(25 * 3600));
  }

  @Test
  void enrollLinksCredentialAndMarkesTokenRedeemed() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);
    Credential cred = credential();

    Identity result = service.enroll(token.value(), cred);

    assertThat(result.id()).isEqualTo(identity.id());
    assertThat(tokenRepository.findById(token.id()))
        .isPresent()
        .get()
        .satisfies(t -> assertThat(t.status()).isEqualTo(EnrollmentTokenStatus.REDEEMED));
    assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(cred.id()))
        .isPresent()
        .contains(identity.id());
  }

  @Test
  void enrollThrowsForUnknownTokenValue() {
    Credential cred = credential();

    assertThatThrownBy(() -> service.enroll("no-such-token", cred))
        .isInstanceOf(EnrollmentTokenNotFoundException.class);
  }

  @Test
  void enrollThrowsForExpiredToken() {
    Identity identity = savedIdentity();
    // Insert a token that is already expired — save with hashed value to match service behaviour
    EnrollmentToken expired = EnrollmentToken.create(identity, Duration.ofHours(-1));
    tokenRepository.save(
        new EnrollmentToken(
            expired.id(),
            expired.identityId(),
            MessageDigests.sha256Hex(expired.value()),
            expired.status(),
            expired.createdAt(),
            expired.expiresAt(),
            null));
    String tokenValue = expired.value();
    Credential cred = credential();

    assertThatThrownBy(() -> service.enroll(tokenValue, cred))
        .isInstanceOf(EnrollmentTokenExpiredException.class);
  }

  @Test
  void enrollThrowsForRedeemedToken() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);
    service.enroll(token.value(), credential());

    // Try to redeem again with a different credential
    String tokenValue = token.value();
    Credential secondCred = credential();
    assertThatThrownBy(() -> service.enroll(tokenValue, secondCred))
        .isInstanceOf(EnrollmentTokenNotPendingException.class);
  }

  @Test
  void enrollThrowsForRevokedToken() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);
    service.revokeToken(token);
    String tokenValue = token.value();
    Credential cred = credential();

    assertThatThrownBy(() -> service.enroll(tokenValue, cred))
        .isInstanceOf(EnrollmentTokenNotPendingException.class);
  }

  @Test
  void revokeTokenMarksTokenRevoked() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);

    service.revokeToken(token);

    assertThat(tokenRepository.findById(token.id()))
        .isPresent()
        .get()
        .satisfies(t -> assertThat(t.status()).isEqualTo(EnrollmentTokenStatus.REVOKED));
  }

  @Test
  void findTokenReturnsTokenByValue() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);

    assertThat(service.findToken(token.value()))
        .isPresent()
        .get()
        .satisfies(t -> assertThat(t.id()).isEqualTo(token.id()));
  }

  @Test
  void findTokenReturnsEmptyForUnknownValue() {
    assertThat(service.findToken("no-such-value")).isEmpty();
  }

  @Test
  void enrollRecordsRedeemedAtTimestamp() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);
    Instant before = Instant.now();

    service.enroll(token.value(), credential());

    assertThat(tokenRepository.findById(token.id()))
        .isPresent()
        .get()
        .satisfies(
            t -> {
              assertThat(t.redeemedAt()).isNotNull();
              assertThat(t.redeemedAt()).isBetween(before, Instant.now());
            });
  }

  @Test
  void createTokenRevokesExistingPendingTokenForSameIdentity() {
    Identity identity = savedIdentity();
    EnrollmentToken first = service.createToken(identity);

    service.createToken(identity);

    assertThat(tokenRepository.findById(first.id()))
        .isPresent()
        .get()
        .satisfies(t -> assertThat(t.status()).isEqualTo(EnrollmentTokenStatus.REVOKED));
  }

  @Test
  void tokenValueStoredInDatabaseIsHashNotRawValue() {
    Identity identity = savedIdentity();
    EnrollmentToken token = service.createToken(identity);

    String storedValue =
        jdbcClient
            .sql("SELECT token_value FROM who_enrollment_token WHERE id = :id")
            .param("id", token.id())
            .query(String.class)
            .single();

    assertThat(storedValue)
        .isNotEqualTo(token.value())
        .isEqualTo(MessageDigests.sha256Hex(token.value()));
  }
}
