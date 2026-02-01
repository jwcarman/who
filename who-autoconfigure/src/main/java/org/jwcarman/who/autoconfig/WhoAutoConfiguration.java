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

import org.jwcarman.who.core.repository.ContactMethodRepository;
import org.jwcarman.who.core.repository.ExternalIdentityRepository;
import org.jwcarman.who.core.repository.InvitationRepository;
import org.jwcarman.who.core.repository.PermissionRepository;
import org.jwcarman.who.core.repository.RolePermissionRepository;
import org.jwcarman.who.core.repository.RoleRepository;
import org.jwcarman.who.core.repository.UserPreferencesRepository;
import org.jwcarman.who.core.repository.UserRepository;
import org.jwcarman.who.core.repository.UserRoleRepository;
import org.jwcarman.who.core.service.ContactMethodService;
import org.jwcarman.who.core.service.IdentityService;
import org.jwcarman.who.core.service.InvitationService;
import org.jwcarman.who.core.service.PreferencesService;
import org.jwcarman.who.core.service.RbacService;
import org.jwcarman.who.core.service.UserProvisioningPolicy;
import org.jwcarman.who.core.service.UserService;
import org.jwcarman.who.core.service.impl.DefaultContactMethodService;
import org.jwcarman.who.core.service.impl.DefaultIdentityService;
import org.jwcarman.who.core.service.impl.DefaultInvitationService;
import org.jwcarman.who.core.service.impl.DefaultPreferencesService;
import org.jwcarman.who.core.service.impl.DefaultRbacService;
import org.jwcarman.who.core.service.impl.DefaultUserService;
import org.jwcarman.who.core.spi.ContactConfirmationNotifier;
import org.jwcarman.who.core.spi.InvitationNotifier;
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
import tools.jackson.databind.ObjectMapper;

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

    @Bean
    @ConditionalOnMissingBean
    public InvitationService invitationService(InvitationRepository invitationRepository,
                                                InvitationNotifier invitationNotifier,
                                                UserService userService,
                                                IdentityService identityService,
                                                ContactMethodService contactMethodService,
                                                ContactMethodRepository contactMethodRepository,
                                                RoleRepository roleRepository,
                                                WhoProperties properties) {
        return new DefaultInvitationService(
                invitationRepository,
                invitationNotifier,
                userService,
                identityService,
                contactMethodService,
                contactMethodRepository,
                roleRepository,
                properties.getInvitation().getExpirationHours(),
                properties.getInvitation().isRequireVerifiedEmail(),
                properties.getInvitation().isTrustIssuerVerification());
    }

    @Bean
    @ConditionalOnMissingBean
    public ContactMethodService contactMethodService(ContactMethodRepository contactMethodRepository,
                                                       UserRepository userRepository,
                                                       ContactConfirmationNotifier contactConfirmationNotifier) {
        return new DefaultContactMethodService(contactMethodRepository, userRepository, contactConfirmationNotifier);
    }
}
