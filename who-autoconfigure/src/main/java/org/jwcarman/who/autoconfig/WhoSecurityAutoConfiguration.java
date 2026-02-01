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
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Auto-configuration for Who Spring Security integration.
 */
@AutoConfiguration
@EnableMethodSecurity
public class WhoSecurityAutoConfiguration {

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

    @Bean
    @ConditionalOnMissingBean
    public WhoAuthenticationConverter jwtAuthenticationConverter(
            IdentityResolver identityResolver,
            UserService userService) {
        return new WhoAuthenticationConverter(identityResolver, userService);
    }
}
