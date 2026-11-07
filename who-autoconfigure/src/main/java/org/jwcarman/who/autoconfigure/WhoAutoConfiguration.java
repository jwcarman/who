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
import java.util.Set;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyCredentialRepository;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredentialRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkAuthenticationProvider;
import org.jwcarman.who.emaillink.EmailLinkGenerationSuccessHandler;
import org.jwcarman.who.emaillink.EmailLinkIssuer;
import org.jwcarman.who.emaillink.EmailLinkLoginMethod;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.emaillink.EmailLinkSettings;
import org.jwcarman.who.emaillink.EmailLinkTokenRepository;
import org.jwcarman.who.emaillink.JdbcEmailCredentialRepository;
import org.jwcarman.who.emaillink.JdbcEmailLinkTokenRepository;
import org.jwcarman.who.emaillink.JsonAuthenticationHandlers;
import org.jwcarman.who.emaillink.JsonGenerateOneTimeTokenRequestResolver;
import org.jwcarman.who.emaillink.JsonOneTimeTokenAuthenticationConverter;
import org.jwcarman.who.emaillink.MailEmailLinkSender;
import org.jwcarman.who.emaillink.WhoOneTimeTokenService;
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
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.util.StringUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot autoconfiguration that wires all Who modules together.
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} so applications can override any
 * of them. Optional module beans (JDBC repositories, RBAC, JWT) are additionally guarded with
 * {@link ConditionalOnClass} so they are only registered when the relevant module is present on the
 * classpath.
 *
 * <p>When {@code who-spring-security} is on the classpath, method security is enabled by default so
 * that {@code @RequiresPermission} is enforced; set {@code who.method-security.enabled=false} to
 * opt out. An application that declares its own {@code @EnableMethodSecurity} is left alone.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
      "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"
    })
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
   * <p>This bean is only registered when no other {@code PermissionsResolver} is present (e.g.,
   * when {@code who-rbac} is not on the classpath). Applications that need permissions should
   * include {@code who-rbac} or register their own bean.
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
   * Creates the core {@link WhoService} that resolves credentials to principals.
   *
   * @param credentialIdentityRepository maps credential UUIDs to identity UUIDs
   * @param identityRepository stores and retrieves identities
   * @param permissionsResolver resolves permissions for an active identity
   * @return a configured {@link WhoService}
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoService whoService(
      CredentialIdentityRepository credentialIdentityRepository,
      IdentityRepository identityRepository,
      PermissionsResolver permissionsResolver) {
    return new WhoService(identityRepository, credentialIdentityRepository, permissionsResolver);
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
        IdentityRoleRepository identityRoleRepository) {
      return new RbacService(roleRepository, rolePermissionRepository, identityRoleRepository);
    }
  }

  /** Registers enrollment beans when {@code who-enrollment} is on the classpath. */
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

  /** Registers JWT beans when {@code who-jwt} is on the classpath. */
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
        JwtCredentialRepository jwtCredentialRepository, WhoService whoService) {
      return new WhoJwtAuthenticationConverter(jwtCredentialRepository, whoService);
    }
  }

  /** Registers API key beans when {@code who-apikey} is on the classpath. */
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
    public ApiKeyService apiKeyService(
        ApiKeyCredentialRepository apiKeyCredentialRepository,
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
          apiKeyCredentialRepository, whoService, properties.getApiKey().getHeaderName());
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

  /** Registers the browser-session beans when {@code who-spring-security} is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository")
  static class PasswordlessConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
        WhoService whoService, Clock clock) {
      return new WhoSessionSecurityContextRepository(whoService, clock);
    }
  }

  /** Registers {@code GET /who/me} in servlet web applications that have Spring MVC. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(
      name = {
        "org.jwcarman.who.spring.security.web.WhoMeController",
        "org.springframework.web.servlet.DispatcherServlet"
      })
  static class WhoMeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WhoMeController whoMeController() {
      return new WhoMeController();
    }
  }

  /**
   * Registers email sign-in link beans when {@code who-email-link} is on the classpath.
   *
   * <p>{@code who.email-link.link-base-url} is required, and {@code who.email-link.resend-interval}
   * must not exceed {@code who.email-link.token-ttl}; startup fails otherwise.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.emaillink.EmailLinkLoginMethod")
  static class EmailLinkConfiguration {

    static final String EXECUTOR_BEAN_NAME = "whoEmailLinkExecutor";

    @Bean
    @ConditionalOnMissingBean
    public EmailLinkSettings emailLinkSettings(WhoProperties properties) {
      WhoProperties.EmailLink emailLink = properties.getEmailLink();
      if (!StringUtils.hasText(emailLink.getLinkBaseUrl())) {
        throw new IllegalStateException(
            "who.email-link.link-base-url must be set to the page that receives sign-in links");
      }
      Duration tokenTtl = emailLink.getTokenTtl();
      Duration resendInterval = emailLink.getResendInterval();
      if (resendInterval.compareTo(tokenTtl) > 0) {
        throw new IllegalStateException(
            "who.email-link.resend-interval ("
                + resendInterval
                + ") must not be longer than who.email-link.token-ttl ("
                + tokenTtl
                + "), or an expired link would block a new one");
      }
      return new EmailLinkSettings(emailLink.getLinkBaseUrl(), tokenTtl, resendInterval);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailCredentialRepository emailCredentialRepository(JdbcClient jdbcClient) {
      return new JdbcEmailCredentialRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailLinkTokenRepository emailLinkTokenRepository(JdbcClient jdbcClient) {
      return new JdbcEmailLinkTokenRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailCredentialService emailCredentialService(
        EmailCredentialRepository emailCredentialRepository,
        CredentialIdentityRepository credentialIdentityRepository) {
      return new EmailCredentialService(emailCredentialRepository, credentialIdentityRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public WhoOneTimeTokenService whoOneTimeTokenService(
        EmailLinkTokenRepository emailLinkTokenRepository,
        Clock clock,
        EmailLinkSettings settings) {
      return new WhoOneTimeTokenService(emailLinkTokenRepository, clock, settings.tokenTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonGenerateOneTimeTokenRequestResolver jsonGenerateOneTimeTokenRequestResolver(
        ObjectProvider<JsonMapper> jsonMapper) {
      return new JsonGenerateOneTimeTokenRequestResolver(jsonMapper(jsonMapper));
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonOneTimeTokenAuthenticationConverter jsonOneTimeTokenAuthenticationConverter(
        ObjectProvider<JsonMapper> jsonMapper) {
      return new JsonOneTimeTokenAuthenticationConverter(jsonMapper(jsonMapper));
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonAuthenticationHandlers jsonAuthenticationHandlers(
        ObjectProvider<JsonMapper> jsonMapper) {
      return new JsonAuthenticationHandlers(jsonMapper(jsonMapper));
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailLinkIssuer emailLinkIssuer(
        EmailCredentialService emailCredentialService,
        WhoService whoService,
        EmailLinkTokenRepository emailLinkTokenRepository,
        EmailLinkSender emailLinkSender,
        EmailLinkSettings settings,
        Clock clock) {
      return new EmailLinkIssuer(
          emailCredentialService,
          whoService,
          emailLinkTokenRepository,
          emailLinkSender,
          settings,
          clock);
    }

    /**
     * Sends sign-in emails off the request thread, so a known and an unknown address answer alike.
     * Not a default candidate, so it never stands in for the application's own executor.
     */
    @Bean(name = EXECUTOR_BEAN_NAME, defaultCandidate = false)
    @ConditionalOnMissingBean(name = EXECUTOR_BEAN_NAME)
    public Executor whoEmailLinkExecutor() {
      SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("who-email-link-");
      executor.setVirtualThreads(true);
      return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailLinkGenerationSuccessHandler emailLinkGenerationSuccessHandler(
        EmailLinkIssuer emailLinkIssuer, @Qualifier(EXECUTOR_BEAN_NAME) Executor executor) {
      return new EmailLinkGenerationSuccessHandler(emailLinkIssuer, executor);
    }

    /**
     * The provider is built here rather than published as a bean: a lone {@code
     * AuthenticationProvider} bean would become Spring Security's global one and switch off the
     * application's {@code UserDetailsService}.
     */
    @Bean
    @ConditionalOnMissingBean
    public EmailLinkLoginMethod emailLinkLoginMethod(
        WhoOneTimeTokenService tokenService,
        JsonGenerateOneTimeTokenRequestResolver generateRequestResolver,
        EmailLinkGenerationSuccessHandler generationSuccessHandler,
        JsonOneTimeTokenAuthenticationConverter authenticationConverter,
        JsonAuthenticationHandlers handlers,
        WhoService whoService) {
      return new EmailLinkLoginMethod(
          tokenService,
          generateRequestResolver,
          generationSuccessHandler,
          authenticationConverter,
          new EmailLinkAuthenticationProvider(tokenService, whoService),
          handlers.success(),
          handlers.failure());
    }

    private static JsonMapper jsonMapper(ObjectProvider<JsonMapper> jsonMapper) {
      return jsonMapper.getIfAvailable(JsonMapper::new);
    }
  }

  /** Sends sign-in links by email when Spring Boot has configured a {@code JavaMailSender}. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.jwcarman.who.emaillink.MailEmailLinkSender",
        "org.springframework.mail.javamail.JavaMailSender"
      })
  static class MailEmailLinkSenderConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmailLinkSender.class)
    @ConditionalOnBean(JavaMailSender.class)
    public MailEmailLinkSender mailEmailLinkSender(
        JavaMailSender javaMailSender, WhoProperties properties) {
      WhoProperties.EmailLink emailLink = properties.getEmailLink();
      if (!StringUtils.hasText(emailLink.getFrom())) {
        throw new IllegalStateException(
            "who.email-link.from must be set to the sender address of sign-in emails");
      }
      return new MailEmailLinkSender(javaMailSender, emailLink.getFrom(), emailLink.getSubject());
    }
  }

  /**
   * Turns on method security so {@code @RequiresPermission} is enforced rather than silently
   * ignored.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  @ConditionalOnClass(name = "org.jwcarman.who.spring.security.RequiresPermission")
  @ConditionalOnProperty(prefix = "who.method-security", name = "enabled", matchIfMissing = true)
  @ConditionalOnMissingBean(
      type =
          "org.springframework.security.config.annotation.method.configuration.PrePostMethodSecurityConfiguration")
  static class MethodSecurityConfiguration {}
}
