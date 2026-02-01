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
import org.jwcarman.who.core.service.EntitlementsService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Converts a JWT to a Spring Security Authentication with WhoPrincipal.
 */
public class WhoAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdentityResolver identityResolver;
    private final EntitlementsService entitlementsService;

    public WhoAuthenticationConverter(
            IdentityResolver identityResolver,
            EntitlementsService entitlementsService) {
        this.identityResolver = identityResolver;
        this.entitlementsService = entitlementsService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getClaimAsString("sub");

        ExternalIdentityKey identityKey = new ExternalIdentityKey(issuer, subject);
        UUID userId = identityResolver.resolveUserId(identityKey);

        if (userId == null) {
            // Identity not found or denied
            return null;
        }

        Set<String> permissions = entitlementsService.resolvePermissions(userId);
        WhoPrincipal principal = new WhoPrincipal(userId, permissions);

        Collection<GrantedAuthority> authorities = permissions.stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());

        return new WhoAuthenticationToken(principal, authorities);
    }

    /**
     * Custom authentication token for Who framework.
     */
    private static class WhoAuthenticationToken extends AbstractAuthenticationToken {
        private final WhoPrincipal principal;

        public WhoAuthenticationToken(WhoPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            WhoAuthenticationToken that = (WhoAuthenticationToken) o;
            return principal.equals(that.principal);
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + principal.hashCode();
            return result;
        }
    }
}
