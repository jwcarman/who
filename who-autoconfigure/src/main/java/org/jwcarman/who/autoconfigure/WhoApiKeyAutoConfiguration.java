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

import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyAuthenticationProvider;
import org.jwcarman.who.apikey.ApiKeyCredentialRepository;
import org.jwcarman.who.apikey.ApiKeyLoginMethod;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Registers API key beans when {@code who-apikey} is on the classpath. */
@AutoConfiguration(after = WhoAutoConfiguration.class)
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.apikey.ApiKeyAuthenticationFilter")
@ConditionalOnBooleanProperty(name = "who.api-key.enabled", matchIfMissing = true)
public class WhoApiKeyAutoConfiguration {

  /**
   * The repository is also this module's {@link CredentialRemover}; its declared type exposes both,
   * so {@link WhoService} finds it without a second bean.
   *
   * @param jdbcClient the JDBC client
   * @return the repository
   */
  @Bean
  @ConditionalOnMissingBean(ApiKeyCredentialRepository.class)
  public JdbcApiKeyCredentialRepository apiKeyCredentialRepository(JdbcClient jdbcClient) {
    return new JdbcApiKeyCredentialRepository(jdbcClient);
  }

  /**
   * Creates and revokes API keys, publishing the credential events.
   *
   * @param apiKeyCredentialRepository stores the keys' hashes
   * @param credentialIdentityRepository links keys to identities
   * @param whoEventPublisher where the credential events are published
   * @param clock stamps the events
   * @return the service
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeyService apiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    return new ApiKeyService(
        apiKeyCredentialRepository, credentialIdentityRepository, whoEventPublisher, clock);
  }

  /**
   * The API key filter. Its provider is not a bean, so it never joins the application's global
   * authentication manager.
   *
   * @param apiKeyCredentialRepository looks keys up by their hash
   * @param whoService resolves a key's credential to its identity
   * @param properties the header name
   * @param whoEventPublisher where API key sign-ins are published
   * @param clock stamps the events
   * @return the filter
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      WhoService whoService,
      WhoProperties properties,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    return new ApiKeyAuthenticationFilter(
        new ApiKeyAuthenticationProvider(
            apiKeyCredentialRepository, whoService, whoEventPublisher, clock),
        properties.getApiKey().getHeaderName());
  }

  /**
   * Mounts the filter in a chain built with {@code WhoSecurity.all()}, and exempts requests that
   * carry a key from CSRF.
   *
   * @param filter the API key filter
   * @param properties the header name
   * @return the login method
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeyLoginMethod apiKeyLoginMethod(
      ApiKeyAuthenticationFilter filter, WhoProperties properties) {
    return new ApiKeyLoginMethod(filter, properties.getApiKey().getHeaderName());
  }

  /**
   * Keeps Spring Boot from also registering the filter with the servlet container; it runs only
   * inside security chains that add it with {@code WhoApiKeySecurity}.
   */
  @Bean
  public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(
      ApiKeyAuthenticationFilter filter) {
    FilterRegistrationBean<ApiKeyAuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
