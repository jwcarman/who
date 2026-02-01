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

import org.jwcarman.who.core.domain.ExternalIdentity;
import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.repository.ExternalIdentityRepository;
import org.jwcarman.who.core.service.UserProvisioningPolicy;

import java.util.UUID;

/**
 * Default implementation of {@link IdentityResolver} with configurable provisioning policy.
 * <p>
 * This resolver attempts to find an existing mapping between an external identity
 * (identified by issuer and subject from OAuth2/JWT claims) and an internal user ID.
 * If no mapping is found, it delegates to a {@link UserProvisioningPolicy} to determine
 * how to handle the unknown identity.
 * <p>
 * The provisioning policy can be configured to either:
 * <ul>
 *   <li>Auto-provision new users ({@link AutoProvisionIdentityPolicy})</li>
 *   <li>Deny access to unknown identities ({@link DenyUnknownIdentityPolicy})</li>
 *   <li>Use a custom policy implementation</li>
 * </ul>
 *
 * @see IdentityResolver
 * @see UserProvisioningPolicy
 * @see AutoProvisionIdentityPolicy
 * @see DenyUnknownIdentityPolicy
 */
public class DefaultIdentityResolver implements IdentityResolver {

    private final ExternalIdentityRepository repository;
    private final UserProvisioningPolicy provisioningPolicy;

    /**
     * Constructs a new DefaultIdentityResolver with the specified dependencies.
     *
     * @param repository the repository for looking up external identity mappings
     * @param provisioningPolicy the policy for handling unknown identities
     */
    public DefaultIdentityResolver(
            ExternalIdentityRepository repository,
            UserProvisioningPolicy provisioningPolicy) {
        this.repository = repository;
        this.provisioningPolicy = provisioningPolicy;
    }

    /**
     * Resolves an external identity to an internal user ID.
     * <p>
     * First attempts to find an existing mapping in the repository. If found, returns
     * the associated user ID. If not found, delegates to the provisioning policy which
     * may either create a new user or return {@code null} to deny access.
     *
     * @param identityKey the external identity key (issuer and subject)
     * @return the internal user ID if found or provisioned, or {@code null} if access is denied
     */
    @Override
    public UUID resolveUserId(ExternalIdentityKey identityKey) {
        return repository.findByIssuerAndSubject(identityKey.issuer(), identityKey.subject())
            .map(ExternalIdentity::userId)
            .orElseGet(() -> provisioningPolicy.handleUnknownIdentity(identityKey));
    }
}
