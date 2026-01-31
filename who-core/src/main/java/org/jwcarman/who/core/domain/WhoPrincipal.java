package org.jwcarman.who.core.domain;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Principal representing an authenticated user with resolved permissions.
 * Used by Spring Security authentication.
 */
public record WhoPrincipal(
    UUID userId,
    String issuer,
    String subject,
    Set<String> permissions
) {
    public WhoPrincipal {
        permissions = Collections.unmodifiableSet(Set.copyOf(permissions));
    }
}
