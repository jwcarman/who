package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalIdentityKeyTest {

    @Test
    void shouldCreateKeyWithIssuerAndSubject() {
        String issuer = "https://auth.example.com";
        String subject = "user123";

        ExternalIdentityKey key = new ExternalIdentityKey(issuer, subject);

        assertThat(key.issuer()).isEqualTo(issuer);
        assertThat(key.subject()).isEqualTo(subject);
    }

    @Test
    void shouldImplementEqualsAndHashCode() {
        ExternalIdentityKey key1 = new ExternalIdentityKey("iss", "sub");
        ExternalIdentityKey key2 = new ExternalIdentityKey("iss", "sub");
        ExternalIdentityKey key3 = new ExternalIdentityKey("iss", "different");

        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
        assertThat(key1).isNotEqualTo(key3);
    }
}
