package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Policy that denies access to unknown external identities.
 */
@Component
public class DenyUnknownIdentityPolicy implements UserProvisioningPolicy {

    private static final Logger log = LoggerFactory.getLogger(DenyUnknownIdentityPolicy.class);

    @Override
    public UUID handleUnknownIdentity(ExternalIdentityKey identityKey) {
        log.warn("Denying access to unknown identity: issuer={}, subject={}",
                identityKey.issuer(), identityKey.subject());
        return null;
    }
}
