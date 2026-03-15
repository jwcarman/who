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
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

/**
 * Spring Security authentication token backed by a resolved {@link WhoPrincipal}.
 *
 * <p>Marked authenticated on creation. Credentials are not retained after authentication.
 */
public class WhoAuthenticationToken extends AbstractAuthenticationToken {

    private final WhoPrincipal principal;

    /**
     * Creates a new authenticated token.
     *
     * @param principal   the resolved principal
     * @param authorities the granted authorities derived from the principal's permissions
     */
    public WhoAuthenticationToken(WhoPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = requireNonNull(principal, "principal must not be null");
        setAuthenticated(true);
    }

    @Override
    public WhoPrincipal getPrincipal() {
        return principal;
    }

    /** Returns {@code null} — credentials are not retained after authentication. */
    @Override
    public Object getCredentials() {
        return null;
    }
}
