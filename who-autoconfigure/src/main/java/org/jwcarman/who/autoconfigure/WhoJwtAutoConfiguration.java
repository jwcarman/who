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
import org.jwcarman.who.jwt.JdbcJwtCredentialRepository;
import org.jwcarman.who.jwt.JwtCredentialRepository;
import org.jwcarman.who.jwt.JwtCredentialService;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers JWT beans when {@code who-jwt} is on the classpath. */
@AutoConfiguration(after = WhoAutoConfiguration.class)
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.jwt.WhoJwtAuthenticationConverter")
public class WhoJwtAutoConfiguration {

  /**
   * The repository is also this module's {@link CredentialRemover}; its declared type exposes both,
   * so {@link WhoService} finds it without a second bean.
   *
   * @param jdbcClient the JDBC client
   * @return the repository
   */
  @Bean
  @ConditionalOnMissingBean(JwtCredentialRepository.class)
  public JdbcJwtCredentialRepository jwtCredentialRepository(JdbcClient jdbcClient) {
    return new JdbcJwtCredentialRepository(jdbcClient);
  }

  /**
   * The converter that signs JWTs in, publishing the sign-in events.
   *
   * @param jwtCredentialRepository looks credentials up by issuer and subject
   * @param whoService resolves a credential to its identity
   * @param whoEventPublisher where the sign-in events are published
   * @param clock stamps the events
   * @return the converter
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoJwtAuthenticationConverter whoJwtAuthenticationConverter(
      JwtCredentialRepository jwtCredentialRepository,
      WhoService whoService,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    return new WhoJwtAuthenticationConverter(
        jwtCredentialRepository, whoService, whoEventPublisher, clock);
  }

  /**
   * Links JWT issuer/subject pairs to identities, publishing the credential events.
   *
   * @param jwtCredentialRepository stores the credentials
   * @param credentialIdentityRepository links credentials to identities
   * @param whoEventPublisher where the credential events are published
   * @param clock stamps the events
   * @return the service
   */
  @Bean
  @ConditionalOnMissingBean
  public JwtCredentialService jwtCredentialService(
      JwtCredentialRepository jwtCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    return new JwtCredentialService(
        jwtCredentialRepository, credentialIdentityRepository, whoEventPublisher, clock);
  }
}
