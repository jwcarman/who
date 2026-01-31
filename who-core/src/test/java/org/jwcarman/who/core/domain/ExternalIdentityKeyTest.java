/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
