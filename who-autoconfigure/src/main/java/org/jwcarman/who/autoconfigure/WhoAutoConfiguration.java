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

import java.time.Clock;
import java.util.Set;

import javax.sql.DataSource;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.repository.SessionRecordRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.jwcarman.who.jdbc.JdbcSessionRecordRepository;
import org.jwcarman.who.rbac.IdentityRoleRepository;
import org.jwcarman.who.rbac.JdbcIdentityRoleRepository;
import org.jwcarman.who.rbac.JdbcRolePermissionRepository;
import org.jwcarman.who.rbac.JdbcRoleRepository;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.RolePermissionRepository;
import org.jwcarman.who.rbac.RoleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.micrometer.observation.ObservationRegistry;

/**
 * who's core autoconfiguration: {@link WhoService}, the clock, the event publisher, the schema
 * initializer, and the JDBC and RBAC beans when those modules are on the classpath. Each sign-in
 * module has its own autoconfiguration, ordered after this one.
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} so applications can override any
 * of them.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
      "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
    })
@EnableConfigurationProperties(WhoProperties.class)
public class WhoAutoConfiguration {

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

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
   * <p>This bean is only registered when no other {@code PermissionsResolver} is present (e.g.,
   * when {@code who-rbac} is not on the classpath). Applications that need permissions should
   * include {@code who-rbac} or register their own bean.
   *
   * @return a resolver that grants nothing
   */
  @Bean
  @ConditionalOnMissingBean
  public PermissionsResolver permissionsResolver() {
    return identity -> Set.of();
  }

  /**
   * The clock who reads the current time from (sign-in times, link expiry).
   *
   * @return the system UTC clock
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock whoClock() {
    return Clock.systemUTC();
  }

  /**
   * Publishes who's events as Spring application events, after the surrounding transaction commits.
   *
   * @param publisher the Spring event publisher
   * @return the publisher who's services use
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoEventPublisher whoEventPublisher(ApplicationEventPublisher publisher) {
    return new SpringWhoEventPublisher(publisher);
  }

  /**
   * Creates the core {@link WhoService} that resolves credentials to principals.
   *
   * @param credentialIdentityRepository maps credential UUIDs to identity UUIDs
   * @param identityRepository stores and retrieves identities
   * @param permissionsResolver resolves permissions for an active identity
   * @param credentialRemovers every credential module's remover, used to delete identities
   * @param whoEventPublisher where who's events are published
   * @param whoClock the clock events are stamped with
   * @param observationRegistry the application's observation registry, if it has exactly one
   * @return a configured {@link WhoService}
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoService whoService(
      CredentialIdentityRepository credentialIdentityRepository,
      IdentityRepository identityRepository,
      PermissionsResolver permissionsResolver,
      ObjectProvider<CredentialRemover> credentialRemovers,
      WhoEventPublisher whoEventPublisher,
      Clock whoClock,
      ObjectProvider<ObservationRegistry> observationRegistry) {
    return new WhoService(
        identityRepository,
        credentialIdentityRepository,
        permissionsResolver,
        credentialRemovers.orderedStream().toList(),
        whoEventPublisher,
        whoClock,
        observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
  }

  /** Registers JDBC repository beans when {@code who-jdbc} is on the classpath. */
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

    @Bean
    @ConditionalOnMissingBean
    public SessionRecordRepository sessionRecordRepository(JdbcClient jdbcClient) {
      return new JdbcSessionRecordRepository(jdbcClient);
    }
  }

  /** Registers RBAC beans when {@code who-rbac} is on the classpath. */
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
    public RbacPermissionsResolver rbacPermissionsResolver(
        IdentityRoleRepository identityRoleRepository,
        RolePermissionRepository rolePermissionRepository) {
      return new RbacPermissionsResolver(identityRoleRepository, rolePermissionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public RbacService rbacService(
        RoleRepository roleRepository,
        RolePermissionRepository rolePermissionRepository,
        IdentityRoleRepository identityRoleRepository,
        WhoEventPublisher whoEventPublisher,
        Clock whoClock) {
      return new RbacService(
          roleRepository,
          rolePermissionRepository,
          identityRoleRepository,
          whoEventPublisher,
          whoClock);
    }
  }
}
