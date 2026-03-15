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

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhoEnrollmentServiceTest extends AbstractEnrollmentTest {

    @Autowired
    private WhoEnrollmentService service;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private CredentialIdentityRepository credentialIdentityRepository;

    @Autowired
    private EnrollmentTokenRepository tokenRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private Identity savedIdentity() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        return identityRepository.save(identity);
    }

    private Credential credential() {
        UUID id = UUID.randomUUID();
        return () -> id;
    }

    @Test
    void createTokenThrowsForUnknownIdentity() {
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createToken(unknownId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTokenReturnsPendingToken() {
        Identity identity = savedIdentity();

        EnrollmentToken token = service.createToken(identity.id());

        assertThat(token.status()).isEqualTo(EnrollmentTokenStatus.PENDING);
        assertThat(token.identityId()).isEqualTo(identity.id());
        assertThat(token.isPending()).isTrue();
    }

    @Test
    void createTokenUsesConfiguredExpirationHours() {
        Identity identity = savedIdentity();
        Instant before = Instant.now();

        EnrollmentToken token = service.createToken(identity.id());

        // default expiration is 24 hours
        assertThat(token.expiresAt()).isAfter(before.plusSeconds(23 * 3600))
                .isBefore(before.plusSeconds(25 * 3600));
    }

    @Test
    void enrollLinksCredentialAndMarkesTokenRedeemed() {
        Identity identity = savedIdentity();
        EnrollmentToken token = service.createToken(identity.id());
        Credential cred = credential();

        Identity result = service.enroll(token.value(), cred);

        assertThat(result.id()).isEqualTo(identity.id());
        assertThat(tokenRepository.findById(token.id()))
                .isPresent().get()
                .satisfies(t -> assertThat(t.status()).isEqualTo(EnrollmentTokenStatus.REDEEMED));
        assertThat(credentialIdentityRepository.findIdentityIdByCredentialId(cred.id()))
                .isPresent().contains(identity.id());
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
        // Insert a token that is already expired
        EnrollmentToken expired = EnrollmentToken.create(identityId(identity), -1);
        tokenRepository.save(expired);

        assertThatThrownBy(() -> service.enroll(expired.value(), credential()))
                .isInstanceOf(EnrollmentTokenExpiredException.class);
    }

    @Test
    void enrollThrowsForRedeemedToken() {
        Identity identity = savedIdentity();
        EnrollmentToken token = service.createToken(identity.id());
        service.enroll(token.value(), credential());

        // Try to redeem again with a different credential
        assertThatThrownBy(() -> service.enroll(token.value(), credential()))
                .isInstanceOf(EnrollmentTokenNotPendingException.class);
    }

    @Test
    void enrollThrowsForRevokedToken() {
        Identity identity = savedIdentity();
        EnrollmentToken token = service.createToken(identity.id());
        service.revokeToken(token.id());

        assertThatThrownBy(() -> service.enroll(token.value(), credential()))
                .isInstanceOf(EnrollmentTokenNotPendingException.class);
    }

    @Test
    void revokeTokenMarksTokenRevoked() {
        Identity identity = savedIdentity();
        EnrollmentToken token = service.createToken(identity.id());

        service.revokeToken(token.id());

        assertThat(tokenRepository.findById(token.id()))
                .isPresent().get()
                .satisfies(t -> assertThat(t.status()).isEqualTo(EnrollmentTokenStatus.REVOKED));
    }

    @Test
    void findTokenReturnsTokenByValue() {
        Identity identity = savedIdentity();
        EnrollmentToken token = service.createToken(identity.id());

        assertThat(service.findToken(token.value())).isPresent()
                .get().satisfies(t -> assertThat(t.id()).isEqualTo(token.id()));
    }

    @Test
    void findTokenReturnsEmptyForUnknownValue() {
        assertThat(service.findToken("no-such-value")).isEmpty();
    }

    private UUID identityId(Identity identity) {
        return identity.id();
    }
}
