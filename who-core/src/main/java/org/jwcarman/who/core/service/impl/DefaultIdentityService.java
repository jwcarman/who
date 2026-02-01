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
package org.jwcarman.who.core.service.impl;

import org.jwcarman.who.core.domain.ExternalIdentity;
import org.jwcarman.who.core.repository.ExternalIdentityRepository;
import org.jwcarman.who.core.repository.UserRepository;
import org.jwcarman.who.core.service.IdentityService;

import java.util.UUID;

/**
 * Core implementation of IdentityService with business logic.
 */
public class DefaultIdentityService implements IdentityService {

    private final UserRepository userRepository;
    private final ExternalIdentityRepository externalIdentityRepository;

    public DefaultIdentityService(UserRepository userRepository,
                                ExternalIdentityRepository externalIdentityRepository) {
        this.userRepository = userRepository;
        this.externalIdentityRepository = externalIdentityRepository;
    }

    @Override
    public void linkExternalIdentity(UUID userId, String issuer, String subject) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Check if identity is already linked to another user
        externalIdentityRepository.findByIssuerAndSubject(issuer, subject)
            .ifPresent(existing -> {
                if (!existing.userId().equals(userId)) {
                    throw new IllegalArgumentException(
                        "External identity is already linked to another user"
                    );
                }
            });

        // Create or update external identity
        ExternalIdentity identity = externalIdentityRepository
            .findByIssuerAndSubject(issuer, subject)
            .orElseGet(() -> ExternalIdentity.create(UUID.randomUUID(), userId, issuer, subject));

        externalIdentityRepository.save(identity);
    }

    @Override
    public void unlinkExternalIdentity(UUID userId, UUID externalIdentityId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Validate external identity exists and is linked to the user
        ExternalIdentity identity = externalIdentityRepository.findById(externalIdentityId)
            .orElseThrow(() -> new IllegalArgumentException(
                "External identity does not exist: " + externalIdentityId
            ));

        if (!identity.userId().equals(userId)) {
            throw new IllegalArgumentException(
                "External identity is not linked to user: " + userId
            );
        }

        externalIdentityRepository.deleteById(externalIdentityId);
    }
}
