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
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyAuthenticationProvider;
import org.jwcarman.who.apikey.ApiKeyCredentialRepository;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredential;
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
import org.jwcarman.who.jwt.JwtCredentialService;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.passkey.JdbcWhoPasskeyCredentialRepository;
import org.jwcarman.who.passkey.JdbcWhoPasskeyUserRepository;
import org.jwcarman.who.passkey.MailPasskeyAddedNotifier;
import org.jwcarman.who.passkey.PasskeyAddedNotifier;
import org.jwcarman.who.passkey.PasskeyCredentialRemover;
import org.jwcarman.who.passkey.PasskeyLoginMethod;
import org.jwcarman.who.passkey.PasskeyNameResolver;
import org.jwcarman.who.passkey.PasskeyService;
import org.jwcarman.who.passkey.PasskeySettings;
import org.jwcarman.who.passkey.WhoPasskeyAuthenticationProvider;
import org.jwcarman.who.passkey.WhoRelyingPartyOperations;
import org.jwcarman.who.rbac.IdentityRoleRepository;
import org.jwcarman.who.rbac.JdbcIdentityRoleRepository;
import org.jwcarman.who.rbac.JdbcRolePermissionRepository;
import org.jwcarman.who.rbac.JdbcRoleRepository;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.RolePermissionRepository;
import org.jwcarman.who.rbac.RoleRepository;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.JsonAuthenticationHandlers;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerExceptionResolver;

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

  private static final Logger log = LoggerFactory.getLogger(WhoAutoConfiguration.class);

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
      Clock whoClock) {
    return new WhoService(
        identityRepository,
        credentialIdentityRepository,
        permissionsResolver,
        credentialRemovers.orderedStream().toList(),
        whoEventPublisher,
        whoClock);
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

  /** Registers JWT beans when {@code who-jwt} is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.jwt.WhoJwtAuthenticationConverter")
  static class JwtConfiguration {

    /**
     * The repository is also this module's {@link CredentialRemover}; its declared type exposes
     * both, so {@link WhoService} finds it without a second bean.
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

  /** Registers API key beans when {@code who-apikey} is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.apikey.ApiKeyAuthenticationFilter")
  static class ApiKeyConfiguration {

    /**
     * The repository is also this module's {@link CredentialRemover}; its declared type exposes
     * both, so {@link WhoService} finds it without a second bean.
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
        WhoService whoService, Clock clock, WhoEventPublisher whoEventPublisher) {
      return new WhoSessionSecurityContextRepository(whoService, clock, whoEventPublisher);
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
   * Hands errors raised in who's security filters to the application's Spring MVC exception
   * resolvers, so the application's {@code @RestControllerAdvice} formats them. {@code
   * DefaultErrorAttributes} is a resolver too, so the composite is picked by its bean name.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(
      name = {
        "org.jwcarman.who.spring.security.web.WhoExceptionHandoff",
        "org.springframework.web.servlet.DispatcherServlet"
      })
  static class ExceptionHandoffConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WhoExceptionHandoff whoExceptionHandoff(
        @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> resolver) {
      HandlerExceptionResolver mvc = resolver.getIfAvailable();
      return new WhoExceptionHandoff(
          mvc == null
              ? null
              : (request, response, exception) ->
                  mvc.resolveException(request, response, null, exception) != null);
    }
  }

  /**
   * Registers email sign-in link beans when {@code who-email-link} is on the classpath and the
   * application opts in by setting {@code who.email-link.link-base-url}.
   *
   * <p>Once opted in, {@code who.email-link.resend-interval} must not exceed {@code
   * who.email-link.token-ttl}, and an {@link EmailLinkSender} must be available; startup fails
   * otherwise.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.emaillink.EmailLinkLoginMethod")
  @ConditionalOnProperty(prefix = "who.email-link", name = "link-base-url")
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
      String codeSecret = emailLink.getCodeSecret();
      // Spring binds an unresolvable ${VAR} as literal text; a long enough name would otherwise
      // pass the length check and key every code with a value printed in the app's own config.
      if (codeSecret != null && codeSecret.contains("${")) {
        throw new IllegalStateException(
            "who.email-link.code-secret is an unresolved placeholder; set the variable it names");
      }
      if (codeSecret == null || codeSecret.isBlank() || codeSecret.length() < 32) {
        throw new IllegalStateException(
            "who.email-link.code-secret must be set to at least 32 characters");
      }
      if (emailLink.getCodeLength() < 6) {
        throw new IllegalStateException("who.email-link.code-length must be at least 6");
      }
      if (emailLink.getCodeMaxAttempts() < 1) {
        throw new IllegalStateException("who.email-link.code-max-attempts must be at least 1");
      }
      return new EmailLinkSettings(
          emailLink.getLinkBaseUrl(),
          tokenTtl,
          resendInterval,
          emailLink.getCodeLength(),
          emailLink.getCodeMaxAttempts(),
          codeSecret);
    }

    /**
     * The repository is also this module's {@link CredentialRemover}; its declared type exposes
     * both, so {@link WhoService} finds it without a second bean.
     *
     * @param jdbcClient the JDBC client
     * @return the repository
     */
    @Bean
    @ConditionalOnMissingBean(EmailCredentialRepository.class)
    public JdbcEmailCredentialRepository emailCredentialRepository(JdbcClient jdbcClient) {
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
        CredentialIdentityRepository credentialIdentityRepository,
        WhoEventPublisher whoEventPublisher,
        Clock clock) {
      return new EmailCredentialService(
          emailCredentialRepository, credentialIdentityRepository, whoEventPublisher, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public WhoOneTimeTokenService whoOneTimeTokenService(Clock clock, EmailLinkSettings settings) {
      return new WhoOneTimeTokenService(clock, settings.tokenTtl());
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
        ObjectProvider<JsonMapper> jsonMapper,
        ObjectProvider<WhoExceptionHandoff> handoff,
        WhoEventPublisher whoEventPublisher,
        Clock clock) {
      return new JsonAuthenticationHandlers(
          jsonMapper(jsonMapper),
          handoff.getIfAvailable(() -> new WhoExceptionHandoff(null)),
          whoEventPublisher,
          clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmailLinkIssuer emailLinkIssuer(
        EmailCredentialService emailCredentialService,
        WhoService whoService,
        EmailLinkTokenRepository emailLinkTokenRepository,
        ObjectProvider<EmailLinkSender> emailLinkSender,
        EmailLinkSettings settings,
        Clock clock,
        WhoEventPublisher whoEventPublisher) {
      EmailLinkSender sender = emailLinkSender.getIfAvailable();
      if (sender == null) {
        throw new IllegalStateException(
            "who.email-link.link-base-url is set but sign-in links cannot be sent: register an"
                + " EmailLinkSender bean, or set spring.mail.host and who.email-link.from");
      }
      return new EmailLinkIssuer(
          emailCredentialService,
          whoService,
          emailLinkTokenRepository,
          sender,
          settings,
          clock,
          whoEventPublisher);
    }

    /**
     * Sends sign-in emails off the request thread, so a known and an unknown address answer alike.
     * Not a default candidate, so it never stands in for the application's own executor.
     *
     * <p>Applies the application's {@link TaskDecorator} beans as Spring Boot does to its own
     * executors (one on its own, several in order), so a {@code ContextPropagatingTaskDecorator}
     * carries the request's trace and logging context to the send.
     */
    @Bean(name = EXECUTOR_BEAN_NAME, defaultCandidate = false)
    @ConditionalOnMissingBean(name = EXECUTOR_BEAN_NAME)
    public Executor whoEmailLinkExecutor(ObjectProvider<TaskDecorator> taskDecorators) {
      SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("who-email-link-");
      executor.setVirtualThreads(true);
      List<TaskDecorator> decorators = taskDecorators.orderedStream().toList();
      if (decorators.size() == 1) {
        executor.setTaskDecorator(decorators.getFirst());
      } else if (!decorators.isEmpty()) {
        executor.setTaskDecorator(new CompositeTaskDecorator(decorators));
      }
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
        WhoService whoService,
        EmailLinkTokenRepository emailLinkTokenRepository,
        EmailLinkSettings settings,
        Clock clock,
        WhoEventPublisher whoEventPublisher) {
      return new EmailLinkLoginMethod(
          tokenService,
          generateRequestResolver,
          generationSuccessHandler,
          authenticationConverter,
          new EmailLinkAuthenticationProvider(
              emailLinkTokenRepository, settings, whoService, clock, whoEventPublisher),
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
  @ConditionalOnProperty(prefix = "who.email-link", name = "link-base-url")
  static class MailEmailLinkSenderConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmailLinkSender.class)
    @ConditionalOnBean(JavaMailSender.class)
    public MailEmailLinkSender mailEmailLinkSender(
        JavaMailSender javaMailSender, WhoProperties properties, Clock clock) {
      WhoProperties.EmailLink emailLink = properties.getEmailLink();
      if (!StringUtils.hasText(emailLink.getFrom())) {
        throw new IllegalStateException(
            "who.email-link.from must be set to the sender address of sign-in emails");
      }
      return new MailEmailLinkSender(
          javaMailSender, emailLink.getFrom(), emailLink.getSubject(), clock);
    }
  }

  /**
   * Registers passkey beans when {@code who-passkey} is on the classpath and the application opts
   * in by setting {@code who.passkey.rp-id}.
   *
   * <p>The provider and the registration guard are built by {@link PasskeyLoginMethod}, not
   * published as beans: a lone {@code AuthenticationProvider} bean would become Spring Security's
   * global one, and a filter bean would be registered with the servlet container. There is exactly
   * one {@link WebAuthnRelyingPartyOperations} bean, since Spring's WebAuthn filters look it up and
   * the provider must verify through the same instance.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.passkey.PasskeyLoginMethod")
  @ConditionalOnProperty(prefix = "who.passkey", name = "rp-id")
  static class PasskeyConfiguration {

    private static final String EMAIL_CREDENTIAL_SERVICE =
        "org.jwcarman.who.emaillink.EmailCredentialService";
    private static final String JAVA_MAIL_SENDER =
        "org.springframework.mail.javamail.JavaMailSender";

    @Bean
    @ConditionalOnMissingBean
    public PasskeySettings passkeySettings(WhoProperties properties) {
      WhoProperties.Passkey passkey = properties.getPasskey();
      String rpId = passkey.getRpId();
      if (!StringUtils.hasText(rpId)) {
        throw new IllegalStateException(
            "who.passkey.rp-id must be set to the application's host, such as app.example.com");
      }
      Set<String> allowedOrigins =
          passkey.getAllowedOrigins().isEmpty()
              ? Set.of("https://" + rpId)
              : passkey.getAllowedOrigins();
      return new PasskeySettings(
          rpId, passkey.getRpName(), allowedOrigins, passkey.getRegistrationWindow());
    }

    /** Registered as a bean so its {@code @Transactional} methods are proxied. */
    @Bean
    @ConditionalOnMissingBean
    public JdbcWhoPasskeyUserRepository whoPasskeyUserRepository(
        JdbcClient jdbcClient,
        CredentialIdentityRepository credentialIdentityRepository,
        WhoEventPublisher whoEventPublisher,
        Clock clock) {
      return new JdbcWhoPasskeyUserRepository(
          jdbcClient, credentialIdentityRepository, whoEventPublisher, clock);
    }

    /** Registered as a bean so its {@code @Transactional} methods are proxied. */
    @Bean
    @ConditionalOnMissingBean
    public JdbcWhoPasskeyCredentialRepository whoPasskeyCredentialRepository(
        JdbcClient jdbcClient,
        CredentialIdentityRepository credentialIdentityRepository,
        WhoEventPublisher whoEventPublisher,
        Clock clock) {
      return new JdbcWhoPasskeyCredentialRepository(
          jdbcClient, credentialIdentityRepository, whoEventPublisher, clock);
    }

    /**
     * Describes passkeys to {@code WhoService.deleteIdentity}, so their revocations carry kind and
     * label. It deletes nothing: an identity's passkeys go with it by cascade.
     *
     * @param credentialRepository who's credential records
     * @return the remover
     */
    @Bean
    @ConditionalOnMissingBean
    public PasskeyCredentialRemover passkeyCredentialRemover(
        JdbcWhoPasskeyCredentialRepository credentialRepository) {
      return new PasskeyCredentialRemover(credentialRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public PasskeyService passkeyService(JdbcWhoPasskeyCredentialRepository credentialRepository) {
      return new PasskeyService(credentialRepository);
    }

    /**
     * Names passkeys by email address when who-email-link is on the classpath, otherwise by
     * identity id.
     */
    @Bean
    @ConditionalOnMissingBean
    public PasskeyNameResolver passkeyNameResolver(ApplicationContext context) {
      if (!ClassUtils.isPresent(EMAIL_CREDENTIAL_SERVICE, context.getClassLoader())) {
        return PasskeyNameResolver.identityId();
      }
      return PasskeyEmails.nameResolver(context);
    }

    /**
     * Emails the owner when a passkey is added, when who-email-link and Spring's mail support are
     * on the classpath; otherwise sends nothing.
     */
    @Bean
    @ConditionalOnMissingBean
    public PasskeyAddedNotifier passkeyAddedNotifier(
        ApplicationContext context, WhoProperties properties) {
      ClassLoader classLoader = context.getClassLoader();
      if (!ClassUtils.isPresent(EMAIL_CREDENTIAL_SERVICE, classLoader)
          || !ClassUtils.isPresent(JAVA_MAIL_SENDER, classLoader)) {
        return noPasskeyAddedEmails("who-email-link or spring-boot-starter-mail is not present");
      }
      return PasskeyEmails.notifier(context, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebAuthnRelyingPartyOperations whoRelyingPartyOperations(
        PasskeySettings settings,
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        PasskeyNameResolver nameResolver,
        PasskeyAddedNotifier notifier,
        WhoEventPublisher whoEventPublisher,
        Clock clock) {
      return new WhoRelyingPartyOperations(
          settings,
          userRepository,
          credentialRepository,
          nameResolver,
          notifier,
          whoEventPublisher,
          clock);
    }

    /**
     * The provider is built here rather than published as a bean: a lone {@code
     * AuthenticationProvider} bean would become Spring Security's global one and switch off the
     * application's {@code UserDetailsService}. The login method builds the registration guard.
     */
    @Bean
    @ConditionalOnMissingBean
    public PasskeyLoginMethod passkeyLoginMethod(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        WebAuthnRelyingPartyOperations rpOperations,
        WhoService whoService,
        WhoSessionSecurityContextRepository sessionRepository,
        ObjectProvider<JsonMapper> jsonMapper,
        Clock clock,
        PasskeySettings settings,
        WhoEventPublisher whoEventPublisher) {
      return new PasskeyLoginMethod(
          userRepository,
          credentialRepository,
          new WhoPasskeyAuthenticationProvider(
              rpOperations, credentialRepository, whoService, whoEventPublisher, clock),
          sessionRepository,
          jsonMapper.getIfAvailable(JsonMapper::new),
          clock,
          settings.registrationWindow());
    }

    /**
     * The passkey beans that use who-email-link and Spring's mail support. Its own class, loaded
     * only once the bean methods above have found those on the classpath.
     */
    private static final class PasskeyEmails {

      private PasskeyEmails() {}

      /**
       * Prefers the session's email address, then the identity's oldest one, when email-link
       * sign-in is on; otherwise the identity id.
       */
      static PasskeyNameResolver nameResolver(ApplicationContext context) {
        EmailCredentialService emails =
            context.getBeanProvider(EmailCredentialService.class).getIfAvailable();
        if (emails == null) {
          return PasskeyNameResolver.identityId();
        }
        return PasskeyNameResolver.preferring(
            credentialId -> emails.findById(credentialId).map(EmailCredential::address),
            identityId -> emails.findOldestFor(identityId).map(EmailCredential::address));
      }

      /**
       * A {@link MailPasskeyAddedNotifier} when email-link sign-in is on, a {@code JavaMailSender}
       * exists and {@code who.passkey.from} is set; otherwise one that sends nothing.
       */
      static PasskeyAddedNotifier notifier(ApplicationContext context, WhoProperties properties) {
        EmailCredentialService emails =
            context.getBeanProvider(EmailCredentialService.class).getIfAvailable();
        if (emails == null) {
          return noPasskeyAddedEmails("email-link sign-in is off (who.email-link.link-base-url)");
        }
        JavaMailSender mailSender = context.getBeanProvider(JavaMailSender.class).getIfAvailable();
        if (mailSender == null) {
          return noPasskeyAddedEmails("there is no JavaMailSender (spring.mail.host)");
        }
        String from = properties.getPasskey().getFrom();
        if (!StringUtils.hasText(from)) {
          return noPasskeyAddedEmails("who.passkey.from is not set");
        }
        return new MailPasskeyAddedNotifier(
            mailSender,
            identityId -> emails.findOldestFor(identityId).map(EmailCredential::address),
            from);
      }
    }

    /**
     * The notifier that sends nothing, logged once at WARN: the email is a security notification,
     * so an app that turned passkeys on should see that owners will not be told.
     */
    private static PasskeyAddedNotifier noPasskeyAddedEmails(String reason) {
      log.warn(
          "Passkey-added emails are off, so owners are not told when a passkey is added: {}",
          reason);
      return (identityId, label) -> {};
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
