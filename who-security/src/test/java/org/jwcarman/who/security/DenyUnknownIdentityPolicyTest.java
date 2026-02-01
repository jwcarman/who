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
package org.jwcarman.who.security;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.ExternalIdentityKey;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DenyUnknownIdentityPolicyTest {

    private final DenyUnknownIdentityPolicy policy = new DenyUnknownIdentityPolicy();

    @Test
    void shouldReturnNullForUnknownIdentity() {
        // Given
        ExternalIdentityKey identityKey = new ExternalIdentityKey("https://auth.example.com", "user123");

        // When
        UUID result = policy.handleUnknownIdentity(identityKey);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldDenyAllUnknownIdentities() {
        // Given
        ExternalIdentityKey identityKey1 = new ExternalIdentityKey("https://auth1.example.com", "user1");
        ExternalIdentityKey identityKey2 = new ExternalIdentityKey("https://auth2.example.com", "user2");

        // When
        UUID result1 = policy.handleUnknownIdentity(identityKey1);
        UUID result2 = policy.handleUnknownIdentity(identityKey2);

        // Then
        assertThat(result1).isNull();
        assertThat(result2).isNull();
    }
}
