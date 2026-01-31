package org.jwcarman.who.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.jpa.repository.ExternalIdentityRepository;
import org.jwcarman.who.jpa.repository.UserRepository;
import org.jwcarman.who.security.AutoProvisionIdentityPolicy;
import org.jwcarman.who.security.DenyUnknownIdentityPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration for Who library.
 */
@AutoConfiguration
@EnableConfigurationProperties(WhoProperties.class)
@ComponentScan(basePackages = {
    "org.jwcarman.who.jpa",
    "org.jwcarman.who.security",
    "org.jwcarman.who.web"
})
public class WhoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "true")
    public UserProvisioningPolicy autoProvisionPolicy(
            UserRepository userRepository,
            ExternalIdentityRepository identityRepository) {
        return new AutoProvisionIdentityPolicy(userRepository, identityRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "false", matchIfMissing = true)
    public UserProvisioningPolicy denyUnknownPolicy() {
        return new DenyUnknownIdentityPolicy();
    }
}
