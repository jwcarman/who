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
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.enrollment.EnrollmentTokenRepository;
import org.jwcarman.who.enrollment.JdbcEnrollmentTokenRepository;
import org.jwcarman.who.enrollment.WhoEnrollmentService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers enrollment beans when {@code who-enrollment} is on the classpath. */
@AutoConfiguration(after = WhoAutoConfiguration.class)
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.enrollment.WhoEnrollmentService")
@ConditionalOnBooleanProperty(name = "who.enrollment.enabled", matchIfMissing = true)
public class WhoEnrollmentAutoConfiguration {

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
      WhoProperties properties,
      WhoEventPublisher whoEventPublisher,
      Clock whoClock) {
    return new WhoEnrollmentService(
        enrollmentTokenRepository,
        identityRepository,
        credentialIdentityRepository,
        properties.getEnrollment().getTokenExpiration(),
        whoEventPublisher,
        whoClock);
  }
}
