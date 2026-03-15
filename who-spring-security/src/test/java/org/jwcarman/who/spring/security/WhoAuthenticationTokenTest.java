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
package org.jwcarman.who.spring.security;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WhoAuthenticationTokenTest {

    @Test
    void principalWithPermissionsProducesMatchingAuthorities() {
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of("a", "b"));
        WhoAuthenticationToken token = new WhoAuthenticationToken(principal);

        assertThat(token.getAuthorities())
                .containsExactlyInAnyOrder(
                        new SimpleGrantedAuthority("a"),
                        new SimpleGrantedAuthority("b"));
    }

    @Test
    void principalWithNoPermissionsProducesEmptyAuthorities() {
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of());
        WhoAuthenticationToken token = new WhoAuthenticationToken(principal);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void getPrincipalReturnsSamePrincipal() {
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of("read"));
        WhoAuthenticationToken token = new WhoAuthenticationToken(principal);

        assertThat(token.getPrincipal()).isSameAs(principal);
    }

    @Test
    void getCredentialsReturnsNull() {
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of());
        WhoAuthenticationToken token = new WhoAuthenticationToken(principal);

        assertThat(token.getCredentials()).isNull();
    }

    @Test
    void tokenIsAuthenticated() {
        WhoPrincipal principal = new WhoPrincipal(UUID.randomUUID(), Set.of());
        WhoAuthenticationToken token = new WhoAuthenticationToken(principal);

        assertThat(token.isAuthenticated()).isTrue();
    }

    @Test
    void nullPrincipalThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new WhoAuthenticationToken(null))
                .withMessage("principal must not be null");
    }
}
