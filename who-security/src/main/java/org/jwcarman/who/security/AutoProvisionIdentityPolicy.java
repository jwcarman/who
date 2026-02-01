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
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.service.IdentityService;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.core.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Provisioning policy that automatically creates new users for unknown external identities.
 * <p>
 * When an authenticated user presents an external identity (from OAuth2/JWT) that is not
 * yet mapped to an internal user, this policy automatically:
 * <ol>
 *   <li>Creates a new user account with {@link UserStatus#ACTIVE} status</li>
 *   <li>Links the external identity to the new user account</li>
 *   <li>Returns the new user ID for immediate authentication</li>
 * </ol>
 * <p>
 * This policy is useful for self-service scenarios where any authenticated external user
 * should be granted access. The new user starts with no roles or permissions until they
 * are explicitly assigned.
 * <p>
 * To enable this policy, set the configuration property:
 * <pre>{@code
 * who.provisioning.auto-provision=true
 * }</pre>
 * <p>
 * All provisioning actions are logged at INFO level for audit purposes.
 *
 * @see UserProvisioningPolicy
 * @see DenyUnknownIdentityPolicy
 */
public class AutoProvisionIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(AutoProvisionIdentityPolicy.class);

    private final UserService userService;
    private final IdentityService identityService;

    /**
     * Constructs a new AutoProvisionIdentityPolicy with required services.
     *
     * @param userService the service for creating new users
     * @param identityService the service for linking external identities
     */
    public AutoProvisionIdentityPolicy(UserService userService,
                                      IdentityService identityService) {
        this.userService = userService;
        this.identityService = identityService;
    }

    /**
     * Handles an unknown external identity by auto-provisioning a new user.
     * <p>
     * Creates a new ACTIVE user, links the external identity to it, and returns
     * the new user ID. The operation is logged for audit purposes.
     *
     * @param identityKey the external identity key (issuer and subject)
     * @return the newly created user's ID
     */
    @Override
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.info("Auto-provisioning new user for identity: issuer={}, subject={}",
                identityKey.issuer(), identityKey.subject());

        // Create new user
        UUID userId = userService.createUser(UserStatus.ACTIVE);

        // Link external identity
        identityService.linkExternalIdentity(userId, identityKey.issuer(), identityKey.subject());

        log.info("Auto-provisioned user {} for identity: issuer={}, subject={}",
                userId, identityKey.issuer(), identityKey.subject());

        return userId;
    }
}
