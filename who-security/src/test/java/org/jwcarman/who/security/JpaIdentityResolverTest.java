package org.jwcarman.who.security;

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaIdentityResolverTest {

    @Mock
    private ExternalIdentityRepository repository;

    @Mock
    private UserProvisioningPolicy provisioningPolicy;

    @InjectMocks
    private JpaIdentityResolver resolver;

    @Test
    void shouldResolveExistingIdentity() {
        String issuer = "https://auth.example.com";
        String subject = "user123";
        UUID userId = UUID.randomUUID();

        ExternalIdentityEntity entity = new ExternalIdentityEntity();
        entity.setUserId(userId);

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.of(entity));

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isEqualTo(userId);
    }

    @Test
    void shouldDelegateToProvisioningPolicyForUnknownIdentity() {
        String issuer = "https://auth.example.com";
        String subject = "newuser";
        UUID newUserId = UUID.randomUUID();

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());
        when(provisioningPolicy.handleUnknownIdentity(any(ExternalIdentityKey.class)))
            .thenReturn(newUserId);

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isEqualTo(newUserId);
    }

    @Test
    void shouldReturnNullWhenProvisioningPolicyDenies() {
        String issuer = "https://auth.example.com";
        String subject = "denied";

        when(repository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());
        when(provisioningPolicy.handleUnknownIdentity(any(ExternalIdentityKey.class)))
            .thenReturn(null);

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);
        UUID resolvedUserId = resolver.resolveUserId(key);

        assertThat(resolvedUserId).isNull();
    }
}
