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

import org.jwcarman.who.core.domain.ExternalIdentityKey;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.service.IdentityService;
import org.jwcarman.who.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoProvisionIdentityPolicyTest {

    @Mock
    private UserService userService;

    @Mock
    private IdentityService identityService;

    @InjectMocks
    private AutoProvisionIdentityPolicy policy;

    @Test
    void shouldCreateNewUserAndIdentity() {
        // Given
        ExternalIdentityKey identityKey = new ExternalIdentityKey("https://auth.example.com", "user123");
        UUID userId = UUID.randomUUID();

        when(userService.createUser(UserStatus.ACTIVE)).thenReturn(userId);

        // When
        UUID result = policy.handleUnknownIdentity(identityKey);

        // Then
        assertThat(result).isEqualTo(userId);

        verify(userService).createUser(UserStatus.ACTIVE);
        verify(identityService).linkExternalIdentity(
            eq(userId),
            eq("https://auth.example.com"),
            eq("user123")
        );
    }

    @Test
    void shouldLinkIdentityToCreatedUser() {
        // Given
        ExternalIdentityKey identityKey = new ExternalIdentityKey("https://sso.company.com", "employee456");
        UUID userId = UUID.randomUUID();

        when(userService.createUser(UserStatus.ACTIVE)).thenReturn(userId);

        // When
        UUID result = policy.handleUnknownIdentity(identityKey);

        // Then
        assertThat(result).isEqualTo(userId);

        verify(userService).createUser(UserStatus.ACTIVE);
        verify(identityService).linkExternalIdentity(
            eq(userId),
            eq("https://sso.company.com"),
            eq("employee456")
        );
    }
}
