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
import java.time.Duration;

import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyAuthenticationProvider;
import org.jwcarman.who.apikey.ApiKeyCredentialRepository;
import org.jwcarman.who.apikey.ApiKeyFormat;
import org.jwcarman.who.apikey.ApiKeyLoginMethod;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.ApiKeysController;
import org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.json.JsonMapper;

/** Registers API key beans when {@code who-apikey} is on the classpath. */
@AutoConfiguration(after = WhoAutoConfiguration.class)
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.apikey.ApiKeyAuthenticationFilter")
@ConditionalOnBooleanProperty(name = "who.api-key.enabled", matchIfMissing = true)
public class WhoApiKeyAutoConfiguration {

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoApiKeyAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

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
   * The shape of new keys, with the configured prefix.
   *
   * @param properties {@code who.api-key.prefix}
   * @return the format
   * @throws IllegalStateException if the prefix breaks the format's rules, so startup fails
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeyFormat apiKeyFormat(WhoProperties properties) {
    try {
      return new ApiKeyFormat(properties.getApiKey().getPrefix());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("who.api-key.prefix: " + e.getMessage(), e);
    }
  }

  /**
   * Makes, lists, rotates and revokes API keys, publishing the credential events.
   *
   * @param apiKeyCredentialRepository stores the keys
   * @param credentialIdentityRepository links keys to identities
   * @param permissionsResolver what an owner holds, checked against a new key's scopes
   * @param apiKeyFormat makes the keys
   * @param properties {@code who.api-key.max-lifetime}
   * @param whoEventPublisher where the credential events are published
   * @param clock stamps keys and events
   * @return the service
   * @throws IllegalStateException if the maximum lifetime is not positive, so startup fails
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeyService apiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      PermissionsResolver permissionsResolver,
      ApiKeyFormat apiKeyFormat,
      WhoProperties properties,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    Duration maxLifetime = properties.getApiKey().getMaxLifetime();
    // binding never leaves it null: an empty value keeps the default
    if (maxLifetime.isNegative() || maxLifetime.isZero()) {
      throw new IllegalStateException(
          "who.api-key.max-lifetime must be positive, got " + maxLifetime);
    }
    return new ApiKeyService(
        apiKeyCredentialRepository,
        credentialIdentityRepository,
        permissionsResolver,
        apiKeyFormat,
        maxLifetime,
        whoEventPublisher,
        clock);
  }

  /**
   * The API key filter. Its provider is not a bean, so it never joins the application's global
   * authentication manager.
   *
   * @param apiKeyCredentialRepository looks keys up by their hash and records their use
   * @param whoService resolves a key's credential to its identity
   * @param apiKeyFormat checks a key's shape before any lookup
   * @param properties the header name and {@code who.api-key.last-used-interval}
   * @param whoEventPublisher where API key sign-ins are published
   * @param clock decides expiry and stamps the events
   * @return the filter
   * @throws IllegalStateException if the last-used interval is not positive, so startup fails
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      WhoService whoService,
      ApiKeyFormat apiKeyFormat,
      WhoProperties properties,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    Duration interval = properties.getApiKey().getLastUsedInterval();
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalStateException(
          "who.api-key.last-used-interval must be positive, got " + interval);
    }
    return new ApiKeyAuthenticationFilter(
        new ApiKeyAuthenticationProvider(
            apiKeyCredentialRepository,
            whoService,
            apiKeyFormat,
            interval,
            whoEventPublisher,
            clock),
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
   * The caller's API keys at {@code /me/api-keys} under {@code who.base-path}, for a browser
   * session or a bearer token.
   *
   * @param apiKeyService makes, lists, rotates and revokes the keys
   * @param sessionRepository reads the browser's who session and its sign-in time
   * @param jsonMapper reads request bodies; a default one when the application has none
   * @param clock the current time
   * @param properties {@code who.security.recent-sign-in}
   * @return the controller
   */
  @Bean
  @ConditionalOnMissingBean
  public ApiKeysController whoApiKeysController(
      ApiKeyService apiKeyService,
      WhoSessionSecurityContextRepository sessionRepository,
      ObjectProvider<JsonMapper> jsonMapper,
      Clock clock,
      WhoProperties properties) {
    return new ApiKeysController(
        apiKeyService,
        sessionRepository,
        jsonMapper.getIfAvailable(JsonMapper::new),
        clock,
        properties.getSecurity().getRecentSignIn());
  }

  /**
   * Keeps Spring Boot from also registering the filter with the servlet container; it runs only
   * inside security chains that add it with {@code WhoApiKeySecurity}.
   *
   * @param filter the API-key filter
   * @return a disabled registration
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
