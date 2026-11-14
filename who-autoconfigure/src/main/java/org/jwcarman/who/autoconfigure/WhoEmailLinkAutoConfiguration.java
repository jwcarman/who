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
import java.util.concurrent.Executor;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
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
import org.jwcarman.who.spring.security.web.JsonAuthenticationHandlers;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns email-link sign-in on when {@code who-email-link} is on the classpath; {@code
 * who.email-link.enabled=false} turns it off.
 *
 * <p>Startup fails with a {@link WhoConfigurationException} when {@code
 * who.email-link.link-base-url} or {@code who.email-link.code-secret} is missing, when {@code
 * who.email-link.resend-interval} exceeds {@code who.email-link.token-ttl}, or when no {@link
 * EmailLinkSender} is available.
 */
@AutoConfiguration(
    after = WhoAutoConfiguration.class,
    afterName = "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration")
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.emaillink.EmailLinkLoginMethod")
@ConditionalOnBooleanProperty(name = "who.email-link.enabled", matchIfMissing = true)
public class WhoEmailLinkAutoConfiguration {

  static final String EXECUTOR_BEAN_NAME = "whoEmailLinkExecutor";

  private static final String JAVA_MAIL_SENDER = "org.springframework.mail.javamail.JavaMailSender";

  @Bean
  @ConditionalOnMissingBean
  public EmailLinkSettings emailLinkSettings(WhoProperties properties) {
    WhoProperties.EmailLink emailLink = properties.getEmailLink();
    if (!StringUtils.hasText(emailLink.getLinkBaseUrl())) {
      throw new WhoConfigurationException(
          "who.email-link.link-base-url",
          "must be set to the page that receives sign-in links",
          "Set it to the URL of your sign-in page, such as https://app.example.com/signin."
              + " Or turn email-link sign-in off with who.email-link.enabled=false.");
    }
    Duration tokenTtl = emailLink.getTokenTtl();
    Duration resendInterval = emailLink.getResendInterval();
    if (resendInterval.compareTo(tokenTtl) > 0) {
      throw new WhoConfigurationException(
          "who.email-link.resend-interval",
          "("
              + resendInterval
              + ") must not be longer than who.email-link.token-ttl ("
              + tokenTtl
              + "), or an expired link would block a new one",
          "Shorten who.email-link.resend-interval or lengthen who.email-link.token-ttl.");
    }
    String codeSecret = emailLink.getCodeSecret();
    // Spring binds an unresolvable ${VAR} as literal text; a long enough name would otherwise
    // pass the length check and key every code with a value printed in the app's own config.
    if (codeSecret != null && codeSecret.contains("${")) {
      throw new WhoConfigurationException(
          "who.email-link.code-secret",
          "is an unresolved placeholder",
          "Set the environment variable or property it names."
              + " Or turn email-link sign-in off with who.email-link.enabled=false.");
    }
    if (codeSecret == null || codeSecret.isBlank() || codeSecret.length() < 32) {
      throw new WhoConfigurationException(
          "who.email-link.code-secret",
          "must be set to at least 32 characters",
          "Set it to a random secret of at least 32 characters, kept out of source control."
              + " Or turn email-link sign-in off with who.email-link.enabled=false.");
    }
    if (emailLink.getCodeLength() < 6) {
      throw new WhoConfigurationException(
          "who.email-link.code-length", "must be at least 6", "Set it to 6 or more.");
    }
    if (emailLink.getCodeMaxAttempts() < 1) {
      throw new WhoConfigurationException(
          "who.email-link.code-max-attempts", "must be at least 1", "Set it to 1 or more.");
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
   * The repository is also this module's {@link CredentialRemover}; its declared type exposes both,
   * so {@link WhoService} finds it without a second bean.
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
  public JsonAuthenticationHandlers jsonAuthenticationHandlers(
      ObjectProvider<JsonMapper> jsonMapper,
      ObjectProvider<WhoExceptionHandoff> handoff,
      WhoEventPublisher whoEventPublisher,
      Clock clock,
      ObjectProvider<LastSignInCookie> lastSignInCookie) {
    return new JsonAuthenticationHandlers(
        jsonMapper(jsonMapper),
        handoff.getIfAvailable(() -> new WhoExceptionHandoff(null)),
        whoEventPublisher,
        clock,
        lastSignInCookie.getIfAvailable());
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
      WhoEventPublisher whoEventPublisher,
      ObjectProvider<ObservationRegistry> observationRegistry,
      ResourceLoader resourceLoader) {
    EmailLinkSender sender = emailLinkSender.getIfAvailable();
    if (sender == null
        && !ClassUtils.isPresent(JAVA_MAIL_SENDER, resourceLoader.getClassLoader())) {
      throw new WhoConfigurationException(
          "spring.mail.host",
          "cannot be used, because Spring's mail support is not on the classpath, so sign-in emails"
              + " cannot be sent",
          "Add spring-boot-starter-mail and set spring.mail.host and who.email-link.from, or"
              + " register an EmailLinkSender bean."
              + " Or turn email-link sign-in off with who.email-link.enabled=false.");
    }
    if (sender == null) {
      throw new WhoConfigurationException(
          "spring.mail.host",
          "is not set, so sign-in emails cannot be sent",
          "Set spring.mail.host and who.email-link.from, or register an EmailLinkSender bean."
              + " Or turn email-link sign-in off with who.email-link.enabled=false.");
    }
    return new EmailLinkIssuer(
        emailCredentialService,
        whoService,
        emailLinkTokenRepository,
        sender,
        settings,
        clock,
        whoEventPublisher,
        observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
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
   * The provider and the converter are built here rather than published as beans: a lone {@code
   * AuthenticationProvider} bean would become Spring Security's global one and switch off the
   * application's {@code UserDetailsService}, and Spring's resource server takes any {@code
   * AuthenticationConverter} bean as its own, so bearer requests would be read as sign-in codes.
   */
  @Bean
  @ConditionalOnMissingBean
  public EmailLinkLoginMethod emailLinkLoginMethod(
      WhoOneTimeTokenService tokenService,
      JsonGenerateOneTimeTokenRequestResolver generateRequestResolver,
      EmailLinkGenerationSuccessHandler generationSuccessHandler,
      ObjectProvider<JsonMapper> jsonMapper,
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
        new JsonOneTimeTokenAuthenticationConverter(jsonMapper(jsonMapper)),
        new EmailLinkAuthenticationProvider(
            emailLinkTokenRepository, settings, whoService, clock, whoEventPublisher),
        handlers.success(),
        handlers.failure());
  }

  private static JsonMapper jsonMapper(ObjectProvider<JsonMapper> jsonMapper) {
    return jsonMapper.getIfAvailable(JsonMapper::new);
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
        JavaMailSender javaMailSender, WhoProperties properties, Clock clock) {
      WhoProperties.EmailLink emailLink = properties.getEmailLink();
      if (!StringUtils.hasText(emailLink.getFrom())) {
        throw new WhoConfigurationException(
            "who.email-link.from",
            "must be set to the sender address of sign-in emails",
            "Set it to the address sign-in emails come from, such as no-reply@example.com."
                + " Or turn email-link sign-in off with who.email-link.enabled=false.");
      }
      return new MailEmailLinkSender(
          javaMailSender, emailLink.getFrom(), emailLink.getSubject(), clock);
    }
  }
}
