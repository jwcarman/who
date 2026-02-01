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
package org.jwcarman.who.autoconfig;

import org.jwcarman.who.core.service.UserService;
import org.jwcarman.who.security.IdentityResolver;
import org.jwcarman.who.security.WhoAuthenticationConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Boot auto-configuration for Who Spring Security integration.
 * <p>
 * This configuration sets up the Spring Security infrastructure required for OAuth2/JWT
 * authentication with the Who framework, including:
 * <ul>
 *   <li>Security filter chains for various endpoints</li>
 *   <li>JWT authentication converter for mapping external identities to internal users</li>
 *   <li>Method-level security annotations via {@code @EnableMethodSecurity}</li>
 * </ul>
 * <p>
 * All beans can be overridden by providing custom implementations in your application configuration.
 */
@AutoConfiguration
@EnableMethodSecurity
public class WhoSecurityAutoConfiguration {

    /**
     * Provides a security filter chain for invitation acceptance endpoints.
     * <p>
     * This filter chain has {@code @Order(1)} to ensure it is processed before other filter chains.
     * It matches the {@code /api/invitations/accept} endpoint and configures it as a stateless
     * OAuth2 resource server endpoint that accepts JWT tokens for authentication.
     * <p>
     * This special configuration allows invitation acceptance to occur without requiring the user
     * to already be provisioned in the system - the invitation token itself provides authentication.
     * CSRF protection is disabled as this is a stateless API endpoint.
     * <p>
     * This bean is only created if no bean named "invitationAcceptanceFilterChain" is already defined.
     *
     * @param http the HttpSecurity builder for configuring security
     * @param jwtDecoder the JWT decoder for validating tokens
     * @return the configured SecurityFilterChain
     * @throws Exception if an error occurs during security configuration
     */
    @Bean
    @Order(1)
    @ConditionalOnMissingBean(name = "invitationAcceptanceFilterChain")
    public SecurityFilterChain invitationAcceptanceFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder) {
        http
            .securityMatcher("/api/invitations/accept")
            .authorizeHttpRequests(authorize -> authorize
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Provides the JWT authentication converter for translating JWTs into Who authentication tokens.
     * <p>
     * This converter extracts the external identity information from JWT tokens (issuer and subject),
     * resolves them to internal user IDs using the {@link IdentityResolver}, loads the user's roles
     * and permissions, and creates a {@link org.jwcarman.who.core.domain.WhoPrincipal} containing
     * the complete user identity and authorization information.
     * <p>
     * This bean is only created if no custom JWT authentication converter is defined.
     *
     * @param identityResolver the resolver for mapping external identities to internal user IDs
     * @param userService the user service for loading user details and permissions
     * @return a WhoAuthenticationConverter instance
     */
    @Bean
    @ConditionalOnMissingBean
    public WhoAuthenticationConverter jwtAuthenticationConverter(
            IdentityResolver identityResolver,
            UserService userService) {
        return new WhoAuthenticationConverter(identityResolver, userService);
    }
}
