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
 * Policy that denies access to unknown external identities.
 */
public class DenyUnknownIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(DenyUnknownIdentityPolicy.class);

    @Override
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.warn("Denying access to unknown identity: issuer={}, subject={}",
                identityKey.issuer(), identityKey.subject());
        return null;
    }
}
