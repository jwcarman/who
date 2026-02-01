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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Auto-configuration for Who Spring Security integration.
 */
@AutoConfiguration
@EnableMethodSecurity
public class WhoSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            IdentityResolver identityResolver,
            UserService userService) {
        WhoAuthenticationConverter converter =
            new WhoAuthenticationConverter(identityResolver, userService);

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(jwt ->
            converter.convert(jwt).getAuthorities());
        jwtConverter.setPrincipalClaimName("sub");

        return jwtConverter;
    }
}
