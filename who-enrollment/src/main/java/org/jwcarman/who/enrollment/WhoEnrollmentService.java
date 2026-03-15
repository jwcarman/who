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
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing enrollment tokens that link credentials to identities.
 *
 * <p>The application is responsible for delivering the token value to the user (e.g. via email)
 * and for creating the {@link Credential} before calling {@link #enroll(String, Credential)}.
 * This service only handles the token lifecycle and the credential-to-identity link.
 */
public class WhoEnrollmentService {

    private final EnrollmentTokenRepository enrollmentTokenRepository;
    private final IdentityRepository identityRepository;
    private final CredentialIdentityRepository credentialIdentityRepository;
    private final int expirationHours;

    public WhoEnrollmentService(EnrollmentTokenRepository enrollmentTokenRepository,
                                IdentityRepository identityRepository,
                                CredentialIdentityRepository credentialIdentityRepository,
                                int expirationHours) {
        this.enrollmentTokenRepository = enrollmentTokenRepository;
        this.identityRepository = identityRepository;
        this.credentialIdentityRepository = credentialIdentityRepository;
        this.expirationHours = expirationHours;
    }

    /**
     * Creates and persists a new enrollment token for the given identity.
     *
     * <p>The caller is responsible for delivering {@code token.value()} to the user.
     *
     * @param identityId the identity to enroll a credential for
     * @return the newly created {@code PENDING} token
     * @throws IllegalArgumentException if no identity with the given id exists
     */
    public EnrollmentToken createToken(UUID identityId) {
        if (!identityRepository.existsById(identityId)) {
            throw new IllegalArgumentException("Identity not found: " + identityId);
        }
        return enrollmentTokenRepository.save(EnrollmentToken.create(identityId, expirationHours));
    }

    /**
     * Redeems an enrollment token, linking the credential to the identity.
     *
     * <p>The token must be {@code PENDING} and not expired. On success the token is marked
     * {@code REDEEMED} and the credential is linked to the identity.
     *
     * @param tokenValue the shareable token value given to the user
     * @param credential the credential to link
     * @return the identity the credential was linked to
     * @throws EnrollmentTokenNotFoundException  if no token with the given value exists
     * @throws EnrollmentTokenExpiredException   if the token has passed its expiration time
     * @throws EnrollmentTokenNotPendingException if the token is already redeemed or revoked
     */
    @Transactional
    public Identity enroll(String tokenValue, Credential credential) {
        EnrollmentToken token = enrollmentTokenRepository.findByValue(tokenValue)
                .orElseThrow(() -> new EnrollmentTokenNotFoundException(tokenValue));

        if (token.isExpired()) {
            throw new EnrollmentTokenExpiredException(token.id());
        }
        if (token.status() != EnrollmentTokenStatus.PENDING) {
            throw new EnrollmentTokenNotPendingException(token.id(), token.status());
        }

        credentialIdentityRepository.link(credential.id(), token.identityId());
        enrollmentTokenRepository.save(token.redeem());

        return identityRepository.findById(token.identityId())
                .orElseThrow(() -> new IllegalStateException("Identity disappeared during enrollment: " + token.identityId()));
    }

    /**
     * Revokes an enrollment token, preventing it from being redeemed.
     *
     * @param tokenId the id of the token to revoke
     * @throws EnrollmentTokenNotFoundException if no token with the given id exists
     */
    public void revokeToken(UUID tokenId) {
        EnrollmentToken token = enrollmentTokenRepository.findById(tokenId)
                .orElseThrow(() -> new EnrollmentTokenNotFoundException(tokenId.toString()));
        enrollmentTokenRepository.save(token.revoke());
    }

    /**
     * Looks up a token by its shareable value without side effects.
     *
     * @param tokenValue the token value
     * @return the token, or empty if not found
     */
    public Optional<EnrollmentToken> findToken(String tokenValue) {
        return enrollmentTokenRepository.findByValue(tokenValue);
    }
}
