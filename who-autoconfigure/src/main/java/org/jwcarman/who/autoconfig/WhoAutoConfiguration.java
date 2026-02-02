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
 * Spring Boot auto-configuration for the Who identity and entitlements framework.
 * <p>
 * This configuration automatically sets up the core Who services and infrastructure components
 * when the library is on the classpath. It provides default implementations that can be overridden
 * by defining custom beans.
 * <p>
 * Component scanning is enabled for:
 * <ul>
 *   <li>{@code org.jwcarman.who.jdbc.repository} - JDBC repository implementations</li>
 *   <li>{@code org.jwcarman.who.security} - Spring Security integration components</li>
 *   <li>{@code org.jwcarman.who.web} - REST controllers (if who-web module is included)</li>
 * </ul>
 * <p>
 * Configuration properties are bound from {@link WhoProperties}.
 *
 * @see WhoProperties
 */
@AutoConfiguration
@EnableConfigurationProperties(WhoProperties.class)
@ComponentScan(basePackages = {
    "org.jwcarman.who.jdbc.repository",
    "org.jwcarman.who.security",
    "org.jwcarman.who.web"
})
public class WhoAutoConfiguration {

    /**
     * Provides a default Jackson ObjectMapper for JSON serialization/deserialization.
     * <p>
     * This bean is only created if no other ObjectMapper bean is defined in the application context.
     * Used by {@link PreferencesService} for storing user preferences as JSON.
     *
     * @return a new ObjectMapper instance
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Provides the default identity resolver for mapping external identities to internal user IDs.
     * <p>
     * The identity resolver is responsible for linking OAuth2/JWT subject identifiers from
     * external identity providers to stable internal user UUIDs. This bean is only created
     * if no custom IdentityResolver bean is defined.
     *
     * @param repository the repository for managing external identity mappings
     * @param provisioningPolicy the policy for handling unknown identities
     * @return a DefaultIdentityResolver instance
     */
    @Bean
    @ConditionalOnMissingBean
    public IdentityResolver identityResolver(ExternalIdentityRepository repository,
                                              UserProvisioningPolicy provisioningPolicy) {
        return new DefaultIdentityResolver(repository, provisioningPolicy);
    }

    /**
     * Provides the default user service for managing user accounts and role assignments.
     * <p>
     * The user service handles user lifecycle operations, role assignments, and permission
     * queries. This bean is only created if no custom UserService bean is defined.
     *
     * @param userRepository repository for user persistence
     * @param roleRepository repository for role persistence
     * @param userRoleRepository repository for user-role assignments
     * @param rolePermissionRepository repository for role-permission assignments
     * @return a DefaultUserService instance
     */
    @Bean
    @ConditionalOnMissingBean
    public UserService userService(UserRepository userRepository,
                                    RoleRepository roleRepository,
                                    UserRoleRepository userRoleRepository,
                                    RolePermissionRepository rolePermissionRepository) {
        return new DefaultUserService(userRepository, roleRepository, userRoleRepository, rolePermissionRepository);
    }

    /**
     * Provides the default RBAC service for managing roles and permissions.
     * <p>
     * The RBAC service handles creation and deletion of roles, assignment of permissions to roles,
     * and querying of role-permission relationships. This bean is only created if no custom
     * RbacService bean is defined.
     *
     * @param roleRepository repository for role persistence
     * @param rolePermissionRepository repository for role-permission assignments
     * @param userRoleRepository repository for user-role assignments
     * @param permissionRepository repository for permission persistence
     * @return a DefaultRbacService instance
     */
    @Bean
    @ConditionalOnMissingBean
    public RbacService rbacService(RoleRepository roleRepository,
                                    RolePermissionRepository rolePermissionRepository,
                                    UserRoleRepository userRoleRepository,
                                    PermissionRepository permissionRepository) {
        return new DefaultRbacService(roleRepository, rolePermissionRepository, userRoleRepository, permissionRepository);
    }

    /**
     * Provides the default identity service for managing external identity mappings.
     * <p>
     * The identity service handles linking and unlinking external identities (from OAuth2/JWT
     * providers) to internal user accounts. This bean is only created if no custom IdentityService
     * bean is defined.
     *
     * @param userRepository repository for user persistence
     * @param externalIdentityRepository repository for external identity mappings
     * @return a DefaultIdentityService instance
     */
    @Bean
    @ConditionalOnMissingBean
    public IdentityService identityService(UserRepository userRepository,
                                            ExternalIdentityRepository externalIdentityRepository) {
        return new DefaultIdentityService(userRepository, externalIdentityRepository);
    }

    /**
     * Provides the default preferences service for managing user-specific preferences.
     * <p>
     * The preferences service stores and retrieves user preferences as JSON, organized by namespace.
     * This bean is only created if no custom PreferencesService bean is defined.
     *
     * @param userPreferencesRepository repository for preferences persistence
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return a DefaultPreferencesService instance
     */
    @Bean
    @ConditionalOnMissingBean
    public PreferencesService preferencesService(UserPreferencesRepository userPreferencesRepository,
                                                  ObjectMapper objectMapper) {
        return new DefaultPreferencesService(userPreferencesRepository, objectMapper);
    }

    /**
     * Provides the auto-provisioning policy for handling unknown external identities.
     * <p>
     * When enabled via {@code who.provisioning.auto-provision=true}, this policy automatically
     * creates new user accounts when an authenticated external identity is not yet mapped to
     * an internal user. This bean is only created if no custom UserProvisioningPolicy is defined
     * and auto-provisioning is explicitly enabled.
     *
     * @param userService the user service for creating new users
     * @param identityService the identity service for linking external identities
     * @return an AutoProvisionIdentityPolicy instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "who.provisioning.auto-provision", havingValue = "true")
    public UserProvisioningPolicy autoProvisionPolicy(
            UserService userService,
            IdentityService identityService) {
        return new AutoProvisionIdentityPolicy(userService, identityService);
    }

    /**
     * Provides the deny-unknown policy for handling unknown external identities.
     * <p>
     * This is the default policy when {@code who.provisioning.auto-provision} is not set or is
     * set to {@code false}. This policy rejects authentication attempts from external identities
     * that are not already mapped to internal users, requiring explicit user provisioning.
     * This bean is only created if no custom UserProvisioningPolicy is defined.
     *
     * @return a DenyUnknownIdentityPolicy instance
     */
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
    public ContactConfirmationNotifier contactConfirmationNotifier() {
        return new NoOpContactConfirmationNotifier();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContactMethodService contactMethodService(ContactMethodRepository contactMethodRepository,
                                                       UserRepository userRepository,
                                                       ContactConfirmationNotifier contactConfirmationNotifier) {
        return new DefaultContactMethodService(contactMethodRepository, userRepository, contactConfirmationNotifier);
    }
}
