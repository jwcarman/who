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
package org.jwcarman.who.jwt;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static java.util.Objects.requireNonNull;

/**
 * Converts a Spring Security {@link Jwt} (already validated by the OAuth2 resource server) into a
 * {@link WhoAuthenticationToken} by resolving the JWT's issuer/subject pair to a {@link WhoPrincipal}.
 *
 * <p>Returns {@code null} — causing Spring Security to treat the request as unauthenticated — when:
 * <ul>
 *   <li>no {@link JwtCredential} is registered for the issuer/subject pair, or</li>
 *   <li>{@link WhoService#resolve} finds no active identity linked to the credential.</li>
 * </ul>
 *
 * <p>This converter does <em>not</em> auto-provision identities. The credential must be explicitly
 * registered before access is granted.
 */
public class WhoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtCredentialRepository jwtCredentialRepository;
    private final WhoService whoService;

    /**
     * Creates a new converter.
     *
     * @param jwtCredentialRepository repository for looking up registered JWT credentials
     * @param whoService              service for resolving a credential to a principal
     */
    public WhoJwtAuthenticationConverter(JwtCredentialRepository jwtCredentialRepository, WhoService whoService) {
        this.jwtCredentialRepository = requireNonNull(jwtCredentialRepository,
                "jwtCredentialRepository must not be null");
        this.whoService = requireNonNull(whoService, "whoService must not be null");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return jwtCredentialRepository.findByIssuerAndSubject(jwt.getIssuer().toString(), jwt.getSubject())
                .flatMap(whoService::resolve)
                .map(principal -> new WhoAuthenticationToken(
                        principal,
                        principal.permissions().stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                ))
                .orElse(null);
    }
}
