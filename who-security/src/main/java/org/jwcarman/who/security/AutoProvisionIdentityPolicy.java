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
package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.jwcarman.who.jpa.entity.UserEntity;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.jwcarman.who.jpa.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Policy that automatically provisions new users for unknown external identities.
 */
@Component
public class AutoProvisionIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(AutoProvisionIdentityPolicy.class);

    private final UserRepository userRepository;
    private final ExternalIdentityRepository externalIdentityRepository;

    public AutoProvisionIdentityPolicy(UserRepository userRepository,
                                      ExternalIdentityRepository externalIdentityRepository) {
        this.userRepository = userRepository;
        this.externalIdentityRepository = externalIdentityRepository;
    }

    @Override
    @Transactional
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.info("Auto-provisioning new user for identity: issuer={}, subject={}",
                identityKey.issuer(), identityKey.subject());

        // Create new user
        UserEntity user = new UserEntity();
        user = userRepository.save(user);

        // Create external identity
        ExternalIdentityEntity identity = new ExternalIdentityEntity();
        identity.setUserId(user.getId());
        identity.setIssuer(identityKey.issuer());
        identity.setSubject(identityKey.subject());
        externalIdentityRepository.save(identity);

        log.info("Auto-provisioned user {} for identity: issuer={}, subject={}",
                user.getId(), identityKey.issuer(), identityKey.subject());

        return user.getId();
    }
}
