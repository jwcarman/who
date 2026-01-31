package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WhoPrincipalTest {

    @Test
    void shouldCreatePrincipalWithRequiredFields() {
        UUID userId = UUID.randomUUID();
        String issuer = "https://auth.example.com";
        String subject = "user123";
        Set<String> permissions = Set.of("billing.invoice.read", "billing.invoice.write");

        WhoPrincipal principal = new WhoPrincipal(userId, issuer, subject, permissions);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.issuer()).isEqualTo(issuer);
        assertThat(principal.subject()).isEqualTo(subject);
        assertThat(principal.permissions()).containsExactlyInAnyOrder(
            "billing.invoice.read", "billing.invoice.write");
    }

    @Test
    void shouldReturnImmutablePermissionsSet() {
        UUID userId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(
            userId, "iss", "sub", Set.of("perm1"));

        assertThat(principal.permissions()).isUnmodifiable();
    }
}
