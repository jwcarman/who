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
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JPA-based identity resolver with provisioning policy support.
 */
@Component
public class JpaIdentityResolver implements IdentityResolver {

    private final ExternalIdentityRepository repository;
    private final UserProvisioningPolicy provisioningPolicy;

    public JpaIdentityResolver(
            ExternalIdentityRepository repository,
            UserProvisioningPolicy provisioningPolicy) {
        this.repository = repository;
        this.provisioningPolicy = provisioningPolicy;
    }

    @Override
    public UUID resolveUserId(ExternalIdentityKey identityKey) {
        return repository.findByIssuerAndSubject(identityKey.issuer(), identityKey.subject())
            .map(entity -> entity.getUserId())
            .orElseGet(() -> provisioningPolicy.handleUnknownIdentity(identityKey));
    }
}
