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

import org.jwcarman.who.admin.AdminGate;
import org.jwcarman.who.admin.EmailLinkIdentityEmails;
import org.jwcarman.who.admin.IdentitiesController;
import org.jwcarman.who.admin.IdentityEmails;
import org.jwcarman.who.admin.InvitationsController;
import org.jwcarman.who.admin.LastAdminGuard;
import org.jwcarman.who.admin.RolesController;
import org.jwcarman.who.admin.WhoAdminPermissions;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registers who's admin endpoints when {@code who-admin} is on the classpath, unless {@code
 * who.admin.enabled=false}. With email sign-in on, they also find identities by address, and {@code
 * POST /invitations} invites people through its {@link InvitationService}.
 */
@AutoConfiguration(
    after = {
      WhoAutoConfiguration.class,
      WhoSpringSecurityAutoConfiguration.class,
      WhoEmailLinkAutoConfiguration.class
    })
@EnableConfigurationProperties(WhoProperties.class)
// who-admin brings who-rbac; the second class is the one RbacService's registration keys on
@ConditionalOnClass(
    name = {
      "org.jwcarman.who.admin.IdentitiesController",
      "org.jwcarman.who.rbac.RbacPermissionsResolver"
    })
@ConditionalOnBooleanProperty(name = "who.admin.enabled", matchIfMissing = true)
public class WhoAdminAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(WhoAdminAutoConfiguration.class);

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoAdminAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * Keeps the admin endpoints from leaving no active identity that may manage identities. It counts
   * who-rbac's role holders, so it enforces the rule only when the application's permissions come
   * from who-rbac; with a {@link PermissionsResolver} of the application's own it refuses nothing,
   * and says so at startup. It needs a single {@link PlatformTransactionManager}: without one the
   * admin endpoints, which require it, fail startup rather than run without the rule.
   *
   * @param jdbcClient reads who-rbac's tables and takes the locks
   * @param transactionManager runs each count and change in one transaction
   * @param permissionsResolver the application's resolver, which says whether holders can be
   *     counted
   * @return the guard
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnSingleCandidate(PlatformTransactionManager.class)
  public LastAdminGuard whoLastAdminGuard(
      JdbcClient jdbcClient,
      PlatformTransactionManager transactionManager,
      PermissionsResolver permissionsResolver) {
    boolean enforcing = permissionsResolver instanceof RbacPermissionsResolver;
    if (!enforcing) {
      log.warn(
          "who: the last-admin rule is off: permissions come from {}, not who-rbac, so who cannot"
              + " tell who holds {}",
          permissionsResolver.getClass().getName(),
          WhoAdminPermissions.IDENTITIES_MANAGE);
    }
    return new LastAdminGuard(jdbcClient, transactionManager, enforcing);
  }

  /**
   * The checks every admin endpoint makes first: signed in, the permission, and a recent sign-in
   * for a browser that changes something.
   *
   * @param sessionRepository tells a browser session from a key or a token
   * @param clock the current time
   * @param properties {@code who.security.recent-sign-in}
   * @return the gate
   */
  @Bean
  @ConditionalOnMissingBean
  public AdminGate whoAdminGate(
      WhoSessionSecurityContextRepository sessionRepository,
      Clock clock,
      WhoProperties properties) {
    return new AdminGate(sessionRepository, clock, properties.getSecurity().getRecentSignIn());
  }

  /**
   * The identity admin endpoints at {@code /identities} under {@code who.base-path}.
   *
   * @param identityRepository pages through and reads identities
   * @param whoService deactivates and reactivates identities
   * @param rbacService reads, assigns and removes roles
   * @param emails the identities' addresses, when email sign-in is on
   * @param guard counts each identity's sign-in credentials, if there is one
   * @param lastAdmin keeps the last identity that may manage identities
   * @param gate admits the caller
   * @param jsonMapper reads request bodies; a default one when the application has none
   * @return the controller
   */
  @Bean
  @ConditionalOnMissingBean
  public IdentitiesController whoIdentitiesController(
      IdentityRepository identityRepository,
      WhoService whoService,
      RbacService rbacService,
      ObjectProvider<IdentityEmails> emails,
      ObjectProvider<LastCredentialGuard> guard,
      LastAdminGuard lastAdmin,
      AdminGate gate,
      ObjectProvider<JsonMapper> jsonMapper) {
    return new IdentitiesController(
        identityRepository,
        whoService,
        rbacService,
        emails.getIfAvailable(),
        guard.getIfAvailable(),
        lastAdmin,
        gate,
        jsonMapper.getIfAvailable(JsonMapper::new));
  }

  /**
   * {@code GET /roles} under {@code who.base-path}.
   *
   * @param rbacService lists the roles and their permissions
   * @param gate admits the caller
   * @return the controller
   */
  @Bean
  @ConditionalOnMissingBean
  public RolesController whoRolesController(RbacService rbacService, AdminGate gate) {
    return new RolesController(rbacService, gate);
  }

  /** Finds identities by address when {@code who-email-link} is on the classpath and on. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.emaillink.EmailCredentialService")
  static class EmailConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EmailCredentialService.class)
    public IdentityEmails whoAdminIdentityEmails(
        EmailCredentialService emailCredentialService,
        CredentialIdentityRepository credentialIdentityRepository) {
      return new EmailLinkIdentityEmails(emailCredentialService, credentialIdentityRepository);
    }

    /**
     * {@code POST /invitations} under {@code who.base-path}, when email sign-in's {@link
     * InvitationService} is there.
     *
     * @param invitationService invites the address
     * @param rbacService finds the roles named and reads their permissions
     * @param gate admits the caller
     * @param jsonMapper reads request bodies; a default one when the application has none
     * @return the controller
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(InvitationService.class)
    public InvitationsController whoInvitationsController(
        InvitationService invitationService,
        RbacService rbacService,
        AdminGate gate,
        ObjectProvider<JsonMapper> jsonMapper) {
      return new InvitationsController(
          invitationService, rbacService, gate, jsonMapper.getIfAvailable(JsonMapper::new));
    }
  }
}
