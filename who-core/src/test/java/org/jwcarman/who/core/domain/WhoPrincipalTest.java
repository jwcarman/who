package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WhoPrincipalTest {

    @Test
    void shouldCreatePrincipalWithRequiredFields() {
        UUID userId = UUID.randomUUID();
        Set<String> permissions = Set.of("billing.invoice.read", "billing.invoice.write");

        WhoPrincipal principal = new WhoPrincipal(userId, permissions);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.permissions()).containsExactlyInAnyOrder(
            "billing.invoice.read", "billing.invoice.write");
    }

    @Test
    void shouldReturnImmutablePermissionsSet() {
        UUID userId = UUID.randomUUID();
        WhoPrincipal principal = new WhoPrincipal(userId, Set.of("perm1"));

        assertThat(principal.permissions()).isUnmodifiable();
    }
}
