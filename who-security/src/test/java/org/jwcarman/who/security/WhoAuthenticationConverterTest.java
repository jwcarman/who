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
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhoAuthenticationConverterTest {

    @Mock
    private IdentityResolver identityResolver;

    @Mock
    private UserService userService;

    @InjectMocks
    private WhoAuthenticationConverter converter;

    @Test
    void shouldConvertJwtToAuthentication() {
        String issuer = "https://auth.example.com";
        String subject = "user123";
        UUID userId = UUID.randomUUID();

        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("iss", issuer)
            .claim("sub", subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        when(identityResolver.resolveUserId(any(ExternalIdentityKey.class)))
            .thenReturn(userId);
        when(userService.resolvePermissions(userId))
            .thenReturn(Set.of("billing.invoice.read", "billing.invoice.write"));

        Authentication auth = converter.convert(jwt);

        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(WhoPrincipal.class);

        WhoPrincipal principal = (WhoPrincipal) auth.getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.permissions()).containsExactlyInAnyOrder(
            "billing.invoice.read", "billing.invoice.write");

        assertThat(auth.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("billing.invoice.read", "billing.invoice.write");
    }

    @Test
    void shouldReturnNullWhenIdentityNotResolved() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .claim("iss", "https://auth.example.com")
            .claim("sub", "unknown")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        when(identityResolver.resolveUserId(any(ExternalIdentityKey.class)))
            .thenReturn(null);

        Authentication auth = converter.convert(jwt);

        assertThat(auth).isNull();
    }

    @Test
    void shouldImplementEqualsAndHashCodeForAuthenticationToken() {
        // Given
        UUID userId = UUID.randomUUID();
        Set<String> permissions = Set.of("read", "write");

        when(identityResolver.resolveUserId(any(ExternalIdentityKey.class)))
            .thenReturn(userId);
        when(userService.resolvePermissions(userId))
            .thenReturn(permissions);

        Jwt jwt1 = Jwt.withTokenValue("token1")
            .header("alg", "RS256")
            .claim("iss", "https://auth.example.com")
            .claim("sub", "user123")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        Jwt jwt2 = Jwt.withTokenValue("token2")
            .header("alg", "RS256")
            .claim("iss", "https://auth.example.com")
            .claim("sub", "user123")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        // When
        Authentication auth1 = converter.convert(jwt1);
        Authentication auth2 = converter.convert(jwt2);

        // Then - same principal should produce equal authentication tokens
        assertThat(auth1)
            .isEqualTo(auth2)
            .hasSameHashCodeAs(auth2);
    }

    @Test
    void shouldNotBeEqualWithDifferentPrincipal() {
        // Given
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();

        when(identityResolver.resolveUserId(any(ExternalIdentityKey.class)))
            .thenReturn(userId1, userId2);
        when(userService.resolvePermissions(any(UUID.class)))
            .thenReturn(Set.of("read"));

        Jwt jwt1 = Jwt.withTokenValue("token1")
            .header("alg", "RS256")
            .claim("iss", "https://auth.example.com")
            .claim("sub", "user1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        Jwt jwt2 = Jwt.withTokenValue("token2")
            .header("alg", "RS256")
            .claim("iss", "https://auth.example.com")
            .claim("sub", "user2")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        // When
        Authentication auth1 = converter.convert(jwt1);
        Authentication auth2 = converter.convert(jwt2);

        // Then - different principals should produce unequal tokens
        assertThat(auth1).isNotEqualTo(auth2);
    }
}
