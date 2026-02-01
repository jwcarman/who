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
import org.jwcarman.who.core.repository.ExternalIdentityRepository;
import org.jwcarman.who.core.service.UserProvisioningPolicy;

import java.util.UUID;

/**
 * Identity resolver with provisioning policy support.
 */
public class DefaultIdentityResolver implements IdentityResolver {

    private final ExternalIdentityRepository repository;
    private final UserProvisioningPolicy provisioningPolicy;

    public DefaultIdentityResolver(
            ExternalIdentityRepository repository,
            UserProvisioningPolicy provisioningPolicy) {
        this.repository = repository;
        this.provisioningPolicy = provisioningPolicy;
    }

    @Override
    public UUID resolveUserId(ExternalIdentityKey identityKey) {
        return repository.findByIssuerAndSubject(identityKey.issuer(), identityKey.subject())
            .map(identity -> identity.userId())
            .orElseGet(() -> provisioningPolicy.handleUnknownIdentity(identityKey));
    }
}
