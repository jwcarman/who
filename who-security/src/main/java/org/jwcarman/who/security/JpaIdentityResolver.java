package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JPA-based identity resolver with provisioning policy support.
 */
@Component
public class JpaIdentityResolver implements IdentityResolver {

    private final ExternalIdentityRepository repository;
    private final UserProvisioningPolicy provisioningPolicy;

    public JpaIdentityResolver(
            ExternalIdentityRepository repository,
            UserProvisioningPolicy provisioningPolicy) {
        this.repository = repository;
        this.provisioningPolicy = provisioningPolicy;
    }

    @Override
    public UUID resolveUserId(ExternalIdentityKey identityKey) {
        return repository.findByIssuerAndSubject(identityKey.issuer(), identityKey.subject())
            .map(entity -> entity.getUserId())
            .orElseGet(() -> provisioningPolicy.handleUnknownIdentity(identityKey));
    }
}
