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

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.federated.JdbcFederatedCredentialRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registers the {@code (issuer, subject)} credential's repository and service when {@code
 * who-federated} is on the classpath, which {@code who-jwt} brings. It has no off switch of its
 * own: credentials stay readable and removable even when JWT sign-in is turned off.
 */
@AutoConfiguration(after = WhoAutoConfiguration.class)
@ConditionalOnClass(name = "org.jwcarman.who.federated.JdbcFederatedCredentialRepository")
public class WhoFederatedAutoConfiguration {

  /**
   * The repository is also this module's {@link CredentialRemover}; its declared type exposes both,
   * so {@link WhoService} finds it without a second bean.
   *
   * @param jdbcClient the JDBC client
   * @return the repository
   */
  @Bean
  @ConditionalOnMissingBean(FederatedCredentialRepository.class)
  public JdbcFederatedCredentialRepository federatedCredentialRepository(JdbcClient jdbcClient) {
    return new JdbcFederatedCredentialRepository(jdbcClient);
  }

  /**
   * Links issuer/subject pairs to identities, publishing the credential events.
   *
   * @param federatedCredentialRepository stores the credentials
   * @param credentialIdentityRepository links credentials to identities
   * @param whoEventPublisher where the credential events are published
   * @param clock stamps the events
   * @return the service
   */
  @Bean
  @ConditionalOnMissingBean
  public FederatedCredentialService federatedCredentialService(
      FederatedCredentialRepository federatedCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    return new FederatedCredentialService(
        federatedCredentialRepository, credentialIdentityRepository, whoEventPublisher, clock);
  }
}
