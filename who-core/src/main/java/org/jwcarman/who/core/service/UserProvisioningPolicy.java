package org.jwcarman.who.core.service;

import org.jwcarman.who.core.domain.ExternalIdentityKey;

import java.util.UUID;

/**
 * Policy for handling unknown external identities.
 */
public interface UserProvisioningPolicy {

    /**
     * Handle an unknown external identity.
     *
     * @param identityKey the external identity key
     * @return internal user ID (new or existing), or null to deny access
     */
    UUID handleUnknownIdentity(ExternalIdentityKey identityKey);
}
