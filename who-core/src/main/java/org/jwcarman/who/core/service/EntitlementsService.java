package org.jwcarman.who.core.service;

import java.util.Set;
import java.util.UUID;

/**
 * Service for resolving user entitlements (permissions).
 */
public interface EntitlementsService {

    /**
     * Resolve effective permissions for a user.
     *
     * @param userId the internal user ID
     * @return set of permission strings (e.g., "billing.invoice.read")
     */
    Set<String> resolvePermissions(UUID userId);
}
