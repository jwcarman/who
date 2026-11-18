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
package org.jwcarman.who.apikey;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.RestController;

// Security auto-configuration excluded — integration tests only need the repository and service
// beans.
@Import({JdbcApiKeyCredentialRepository.class})
@SpringBootConfiguration
@EnableAutoConfiguration(
    exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
// who's controllers are the autoconfiguration's to wire, with collaborators this app does not have
@ComponentScan(
    excludeFilters = {
      @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
      @ComponentScan.Filter(
          type = FilterType.CUSTOM,
          classes = AutoConfigurationExcludeFilter.class),
      @ComponentScan.Filter(RestController.class)
    })
class WhoApiKeyTestApp {

  @Bean
  IdentityRepository identityRepository(JdbcClient jdbcClient) {
    return new JdbcIdentityRepository(jdbcClient);
  }

  @Bean
  CredentialIdentityRepository credentialIdentityRepository(JdbcClient jdbcClient) {
    return new JdbcCredentialIdentityRepository(jdbcClient);
  }

  @Bean
  PermissionsResolver permissionsResolver() {
    return identity -> Set.of("task.read", "task.write");
  }

  @Bean
  WhoService whoService(
      IdentityRepository identityRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      PermissionsResolver permissionsResolver) {
    return new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);
  }

  @Bean
  ApiKeyService apiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      PermissionsResolver permissionsResolver) {
    return new ApiKeyService(
        apiKeyCredentialRepository,
        credentialIdentityRepository,
        permissionsResolver,
        new ApiKeyFormat(ApiKeyFormat.DEFAULT_PREFIX),
        Duration.ofDays(365),
        WhoEventPublisher.NOOP,
        Clock.systemUTC());
  }
}
