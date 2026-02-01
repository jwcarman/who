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

import tools.jackson.databind.ObjectMapper;
import org.jwcarman.who.core.repository.*;
import org.jwcarman.who.core.service.*;
import org.jwcarman.who.core.service.impl.*;
import org.jwcarman.who.security.AutoProvisionIdentityPolicy;
import org.jwcarman.who.security.DefaultIdentityResolver;
import org.jwcarman.who.security.DenyUnknownIdentityPolicy;
import org.jwcarman.who.security.IdentityResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for Who library.
 */
@AutoConfiguration
@EnableConfigurationProperties(WhoProperties.class)
@ComponentScan(basePackages = {
    "org.jwcarman.who.jdbc.repository",
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
    public IdentityResolver identityResolver(ExternalIdentityRepository repository,
                                              UserProvisioningPolicy provisioningPolicy) {
        return new DefaultIdentityResolver(repository, provisioningPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public UserService userService(UserRepository userRepository,
                                    RoleRepository roleRepository,
                                    UserRoleRepository userRoleRepository,
                                    RolePermissionRepository rolePermissionRepository) {
        return new DefaultUserService(userRepository, roleRepository, userRoleRepository, rolePermissionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public RbacService rbacService(RoleRepository roleRepository,
                                    RolePermissionRepository rolePermissionRepository,
                                    UserRoleRepository userRoleRepository,
                                    PermissionRepository permissionRepository) {
        return new DefaultRbacService(roleRepository, rolePermissionRepository, userRoleRepository, permissionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityService identityService(UserRepository userRepository,
                                            ExternalIdentityRepository externalIdentityRepository) {
        return new DefaultIdentityService(userRepository, externalIdentityRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public PreferencesService preferencesService(UserPreferencesRepository userPreferencesRepository,
                                                  ObjectMapper objectMapper) {
        return new DefaultPreferencesService(userPreferencesRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "true")
    public UserProvisioningPolicy autoProvisionPolicy(
            UserService userService,
            IdentityService identityService) {
        return new AutoProvisionIdentityPolicy(userService, identityService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "false", matchIfMissing = true)
    public UserProvisioningPolicy denyUnknownPolicy() {
        return new DenyUnknownIdentityPolicy();
    }
}
