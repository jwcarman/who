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
package org.jwcarman.who.autoconfigure;

import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyCredentialRepository;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.enrollment.EnrollmentTokenRepository;
import org.jwcarman.who.enrollment.JdbcEnrollmentTokenRepository;
import org.jwcarman.who.enrollment.WhoEnrollmentService;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.jwcarman.who.jwt.JdbcJwtCredentialRepository;
import org.jwcarman.who.jwt.JwtCredentialRepository;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.rbac.IdentityRoleRepository;
import org.jwcarman.who.rbac.JdbcIdentityRoleRepository;
import org.jwcarman.who.rbac.JdbcRolePermissionRepository;
import org.jwcarman.who.rbac.JdbcRoleRepository;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.RolePermissionRepository;
import org.jwcarman.who.rbac.RoleRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Spring Boot autoconfiguration that wires all Who modules together.
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} so applications can override
 * any of them. Optional module beans (JDBC repositories, RBAC, JWT) are additionally guarded
 * with {@link ConditionalOnClass} so they are only registered when the relevant module is
 * present on the classpath.
 *
 * <p>This class does NOT enable {@code @EnableMethodSecurity} — that is the application's
 * responsibility.
 */
@AutoConfiguration
@EnableConfigurationProperties(WhoProperties.class)
public class WhoAutoConfiguration {

    /**
     * Initializes Who's bundled DDL schemas against the configured {@link DataSource}.
     *
     * @param dataSource the data source to initialize
     * @param properties Who configuration properties
     * @return the database initializer
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    public WhoDataSourceScriptDatabaseInitializer whoDataSourceScriptDatabaseInitializer(
            DataSource dataSource, WhoProperties properties) {
        return new WhoDataSourceScriptDatabaseInitializer(dataSource, properties.getInitializeSchema());
    }

    /**
     * Fallback {@link PermissionsResolver} that grants no permissions.
     *
     * <p>This bean is only registered when no other {@code PermissionsResolver} is present
     * (e.g., when {@code who-rbac} is not on the classpath). Applications that need
     * permissions should include {@code who-rbac} or register their own bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public PermissionsResolver permissionsResolver() {
        return identity -> java.util.Set.of();
    }

    /**
     * Creates the core {@link WhoService} that resolves credentials to principals.
     *
     * @param credentialIdentityRepository maps credential UUIDs to identity UUIDs
     * @param identityRepository           stores and retrieves identities
     * @param permissionsResolver          resolves permissions for an active identity
     * @return a configured {@link WhoService}
     */
    @Bean
    @ConditionalOnMissingBean
    public WhoService whoService(CredentialIdentityRepository credentialIdentityRepository,
                                 IdentityRepository identityRepository,
                                 PermissionsResolver permissionsResolver) {
        return new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);
    }

    /**
     * Registers JDBC repository beans when {@code who-jdbc} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.jwcarman.who.jdbc.JdbcIdentityRepository")
    static class JdbcRepositoriesConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public IdentityRepository identityRepository(JdbcClient jdbcClient) {
            return new JdbcIdentityRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public CredentialIdentityRepository credentialIdentityRepository(JdbcClient jdbcClient) {
            return new JdbcCredentialIdentityRepository(jdbcClient);
        }
    }

    /**
     * Registers RBAC beans when {@code who-rbac} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.jwcarman.who.rbac.RbacPermissionsResolver")
    static class RbacConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public RoleRepository roleRepository(JdbcClient jdbcClient) {
            return new JdbcRoleRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public RolePermissionRepository rolePermissionRepository(JdbcClient jdbcClient) {
            return new JdbcRolePermissionRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public IdentityRoleRepository identityRoleRepository(JdbcClient jdbcClient) {
            return new JdbcIdentityRoleRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public RbacPermissionsResolver rbacPermissionsResolver(IdentityRoleRepository identityRoleRepository,
                                                               RolePermissionRepository rolePermissionRepository) {
            return new RbacPermissionsResolver(identityRoleRepository, rolePermissionRepository);
        }

        @Bean
        @ConditionalOnMissingBean
        public RbacService rbacService(RoleRepository roleRepository,
                                       RolePermissionRepository rolePermissionRepository,
                                       IdentityRoleRepository identityRoleRepository) {
            return new RbacService(roleRepository, rolePermissionRepository, identityRoleRepository);
        }
    }

    /**
     * Registers enrollment beans when {@code who-enrollment} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.jwcarman.who.enrollment.WhoEnrollmentService")
    static class EnrollmentConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EnrollmentTokenRepository enrollmentTokenRepository(JdbcClient jdbcClient) {
            return new JdbcEnrollmentTokenRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public WhoEnrollmentService whoEnrollmentService(
                EnrollmentTokenRepository enrollmentTokenRepository,
                IdentityRepository identityRepository,
                CredentialIdentityRepository credentialIdentityRepository,
                WhoProperties properties) {
            return new WhoEnrollmentService(
                    enrollmentTokenRepository,
                    identityRepository,
                    credentialIdentityRepository,
                    properties.getEnrollment().getTokenExpiration());
        }
    }

    /**
     * Registers JWT beans when {@code who-jwt} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.jwcarman.who.jwt.WhoJwtAuthenticationConverter")
    static class JwtConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public JwtCredentialRepository jwtCredentialRepository(JdbcClient jdbcClient) {
            return new JdbcJwtCredentialRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public WhoJwtAuthenticationConverter whoJwtAuthenticationConverter(
                JwtCredentialRepository jwtCredentialRepository,
                WhoService whoService) {
            return new WhoJwtAuthenticationConverter(jwtCredentialRepository, whoService);
        }
    }

    /**
     * Registers API key beans when {@code who-apikey} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.jwcarman.who.apikey.ApiKeyAuthenticationFilter")
    static class ApiKeyConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ApiKeyCredentialRepository apiKeyCredentialRepository(JdbcClient jdbcClient) {
            return new JdbcApiKeyCredentialRepository(jdbcClient);
        }

        @Bean
        @ConditionalOnMissingBean
        public ApiKeyService apiKeyService(ApiKeyCredentialRepository apiKeyCredentialRepository,
                                           CredentialIdentityRepository credentialIdentityRepository) {
            return new ApiKeyService(apiKeyCredentialRepository, credentialIdentityRepository);
        }

        @Bean
        @ConditionalOnMissingBean
        public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
                ApiKeyCredentialRepository apiKeyCredentialRepository,
                WhoService whoService,
                WhoProperties properties) {
            return new ApiKeyAuthenticationFilter(
                    apiKeyCredentialRepository,
                    whoService,
                    properties.getApiKey().getHeaderName());
        }
    }
}
