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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Provisioning policy that denies access to unknown external identities.
 * <p>
 * When an authenticated user presents an external identity (from OAuth2/JWT) that is not
 * yet mapped to an internal user, this policy rejects the authentication attempt by
 * returning {@code null}. This results in authentication failure and access denial.
 * <p>
 * This is the default policy when auto-provisioning is not enabled. It enforces explicit
 * user provisioning, requiring that users be created and their external identities linked
 * before they can authenticate. This provides tighter control over who can access the system.
 * <p>
 * Typical provisioning flows with this policy:
 * <ul>
 *   <li>Administrator creates a user account via {@link org.jwcarman.who.core.service.UserService}</li>
 *   <li>Administrator links the external identity via {@link org.jwcarman.who.core.service.IdentityService}</li>
 *   <li>Alternatively, users accept an {@link org.jwcarman.who.core.domain.Invitation} which provisions them</li>
 * </ul>
 * <p>
 * This is the default policy when {@code who.provisioning.auto-provision} is {@code false}
 * or not set.
 * <p>
 * All denied access attempts are logged at WARN level for security monitoring.
 *
 * @see UserProvisioningPolicy
 * @see AutoProvisionIdentityPolicy
 */
public class DenyUnknownIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(DenyUnknownIdentityPolicy.class);

    /**
     * Handles an unknown external identity by denying access.
     * <p>
     * Logs a warning and returns {@code null}, which will cause authentication to fail.
     *
     * @param identityKey the external identity key (issuer and subject)
     * @return always {@code null} to deny access
     */
    @Override
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.warn("Denying access to unknown identity: issuer={}, subject={}",
                identityKey.issuer(), identityKey.subject());
        return null;
    }
}
