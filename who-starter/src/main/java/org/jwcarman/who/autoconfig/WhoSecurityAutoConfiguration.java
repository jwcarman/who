package org.jwcarman.who.autoconfig;

import org.jwcarman.who.core.service.EntitlementsService;
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
            EntitlementsService entitlementsService) {
        WhoAuthenticationConverter converter =
            new WhoAuthenticationConverter(identityResolver, entitlementsService);

        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(jwt ->
            converter.convert(jwt).getAuthorities());
        jwtConverter.setPrincipalClaimName("sub");

        return jwtConverter;
    }
}
