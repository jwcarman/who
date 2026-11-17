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

import org.jwcarman.who.admin.EmailLinkIdentityEmails;
import org.jwcarman.who.admin.IdentityEmails;
import org.jwcarman.who.admin.WhoAdminEndpoints;
import org.jwcarman.who.admin.WhoInvitationEndpoints;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
      "org.jwcarman.who.admin.WhoAdminEndpoints",
      "org.jwcarman.who.rbac.RbacPermissionsResolver"
    })
@ConditionalOnBooleanProperty(name = "who.admin.enabled", matchIfMissing = true)
public class WhoAdminAutoConfiguration {

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoAdminAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * The admin endpoints at {@code /identities} and {@code /roles} under {@code who.base-path}, on
   * every browser chain and every stateless chain.
   *
   * @param paths where the endpoints live
   * @param identityRepository pages through and reads identities
   * @param whoService deactivates and reactivates identities
   * @param rbacService reads, assigns and removes roles
   * @param emails the identities' addresses, when email sign-in is on
   * @param guard counts each identity's sign-in credentials, if there is one
   * @param sessionRepository tells a browser session from a key or a token
   * @param handoff the application's exception handling, if it has who's handoff
   * @param jsonMapper the application's JSON mapper, if it has one
   * @param clock the current time
   * @param properties {@code who.security.recent-sign-in}
   * @return the endpoints
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoAdminEndpoints whoAdminEndpoints(
      WhoPaths paths,
      IdentityRepository identityRepository,
      WhoService whoService,
      RbacService rbacService,
      ObjectProvider<IdentityEmails> emails,
      ObjectProvider<LastCredentialGuard> guard,
      WhoSessionSecurityContextRepository sessionRepository,
      ObjectProvider<WhoExceptionHandoff> handoff,
      ObjectProvider<JsonMapper> jsonMapper,
      Clock clock,
      WhoProperties properties) {
    return new WhoAdminEndpoints(
        paths,
        identityRepository,
        whoService,
        rbacService,
        emails.getIfAvailable(),
        guard.getIfAvailable(),
        sessionRepository,
        handoff.getIfAvailable(() -> new WhoExceptionHandoff(null)),
        jsonMapper.getIfAvailable(JsonMapper::new),
        clock,
        properties.getSecurity().getRecentSignIn());
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
     * {@code POST /invitations} under {@code who.base-path}, on every browser chain and every
     * stateless chain, when email sign-in's {@link InvitationService} is there.
     *
     * @param paths where the endpoint lives
     * @param invitationService invites the address
     * @param rbacService finds the roles named and reads their permissions
     * @param sessionRepository tells a browser session from a key or a token
     * @param handoff the application's exception handling, if it has who's handoff
     * @param jsonMapper the application's JSON mapper, if it has one
     * @param clock the current time
     * @param properties {@code who.security.recent-sign-in}
     * @return the endpoint
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(InvitationService.class)
    public WhoInvitationEndpoints whoInvitationEndpoints(
        WhoPaths paths,
        InvitationService invitationService,
        RbacService rbacService,
        WhoSessionSecurityContextRepository sessionRepository,
        ObjectProvider<WhoExceptionHandoff> handoff,
        ObjectProvider<JsonMapper> jsonMapper,
        Clock clock,
        WhoProperties properties) {
      return new WhoInvitationEndpoints(
          paths,
          invitationService,
          rbacService,
          sessionRepository,
          handoff.getIfAvailable(() -> new WhoExceptionHandoff(null)),
          jsonMapper.getIfAvailable(JsonMapper::new),
          clock,
          properties.getSecurity().getRecentSignIn());
    }
  }
}
