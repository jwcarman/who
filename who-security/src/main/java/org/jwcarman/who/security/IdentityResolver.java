package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;

import java.util.UUID;

/**
 * Resolves external identities to internal user IDs.
 */
public interface IdentityResolver {

    /**
     * Resolve external identity to internal user ID.
     *
     * @param identityKey external identity key
     * @return internal user ID, or null if not found/denied
     */
    UUID resolveUserId(ExternalIdentityKey identityKey);
}
