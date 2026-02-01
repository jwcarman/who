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
 * Converts a JWT to a Spring Security Authentication with {@link WhoPrincipal}.
 * <p>
 * This converter is responsible for the core authentication flow in the Who framework:
 * <ol>
 *   <li>Extracts issuer and subject claims from the JWT token</li>
 *   <li>Resolves the external identity to an internal user ID via {@link IdentityResolver}</li>
 *   <li>Loads the user's permissions from the {@link UserService}</li>
 *   <li>Creates a {@link WhoPrincipal} containing the user ID and permissions</li>
 *   <li>Wraps it in a Spring Security {@link AbstractAuthenticationToken}</li>
 * </ol>
 * <p>
 * If the identity cannot be resolved (e.g., unknown identity with deny policy), this converter
 * returns {@code null}, which will result in authentication failure.
 * <p>
 * This converter integrates with Spring Security's OAuth2 resource server JWT support and is
 * typically registered as a bean in {@link org.jwcarman.who.autoconfig.WhoSecurityAutoConfiguration}.
 *
 * @see IdentityResolver
 * @see WhoPrincipal
 * @see UserService
 */
public class WhoAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final IdentityResolver identityResolver;
    private final UserService userService;

    /**
     * Constructs a new WhoAuthenticationConverter with required dependencies.
     *
     * @param identityResolver the resolver for mapping external identities to user IDs
     * @param userService the service for loading user permissions
     */
    public WhoAuthenticationConverter(
            IdentityResolver identityResolver,
            UserService userService) {
        this.identityResolver = identityResolver;
        this.userService = userService;
    }

    /**
     * Converts a JWT token into a Spring Security authentication token.
     * <p>
     * Extracts the issuer ({@code iss}) and subject ({@code sub}) claims from the JWT,
     * resolves them to an internal user ID, loads the user's permissions, and creates
     * an authenticated token with a {@link WhoPrincipal}.
     *
     * @param jwt the JWT token to convert
     * @return an authenticated token with WhoPrincipal, or {@code null} if the identity
     *         cannot be resolved (which results in authentication failure)
     */
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

        Set<String> permissions = userService.resolvePermissions(userId);
        WhoPrincipal principal = new WhoPrincipal(userId, permissions);

        Collection<GrantedAuthority> authorities = permissions.stream()
            .map(SimpleGrantedAuthority::new)
            .collect(Collectors.toSet());

        return new WhoAuthenticationToken(principal, authorities);
    }

    /**
     * Custom authentication token for the Who framework.
     * <p>
     * This token wraps a {@link WhoPrincipal} and is marked as authenticated. It provides
     * Spring Security's {@link GrantedAuthority} collection derived from the user's permissions.
     * <p>
     * The credentials are always {@code null} as this token represents an already-authenticated
     * user via JWT validation.
     */
    private static class WhoAuthenticationToken extends AbstractAuthenticationToken {
        private final WhoPrincipal principal;

        /**
         * Constructs a new authenticated token with the given principal and authorities.
         *
         * @param principal the Who principal containing user ID and permissions
         * @param authorities the granted authorities derived from user permissions
         */
        public WhoAuthenticationToken(WhoPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
            super(authorities);
            this.principal = principal;
            setAuthenticated(true);
        }

        /**
         * Returns {@code null} as credentials are not used in this token.
         * Authentication is based on JWT validation, not stored credentials.
         *
         * @return always {@code null}
         */
        @Override
        public Object getCredentials() {
            return null;
        }

        /**
         * Returns the Who principal containing the user's identity and permissions.
         *
         * @return the WhoPrincipal
         */
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
