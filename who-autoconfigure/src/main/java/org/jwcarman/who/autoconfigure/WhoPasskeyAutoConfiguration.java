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
import java.util.Set;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
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
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import tools.jackson.databind.json.JsonMapper;

/**
 * Registers passkey beans when {@code who-passkey} is on the classpath and the application opts in
 * by setting {@code who.passkey.rp-id}.
 *
 * <p>The provider and the registration guard are built by {@link PasskeyLoginMethod}, not published
 * as beans: a lone {@code AuthenticationProvider} bean would become Spring Security's global one,
 * and a filter bean would be registered with the servlet container. There is exactly one {@link
 * WebAuthnRelyingPartyOperations} bean, since Spring's WebAuthn filters look it up and the provider
 * must verify through the same instance.
 */
@AutoConfiguration(
    after = WhoAutoConfiguration.class,
    afterName = "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration")
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.passkey.PasskeyLoginMethod")
@ConditionalOnBooleanProperty(name = "who.passkey.enabled", matchIfMissing = true)
public class WhoPasskeyAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(WhoPasskeyAutoConfiguration.class);

  private static final String EMAIL_CREDENTIAL_SERVICE =
      "org.jwcarman.who.emaillink.EmailCredentialService";
  private static final String JAVA_MAIL_SENDER = "org.springframework.mail.javamail.JavaMailSender";

  @Bean
  @ConditionalOnMissingBean
  public PasskeySettings passkeySettings(WhoProperties properties) {
    WhoProperties.Passkey passkey = properties.getPasskey();
    String rpId = passkey.getRpId();
    if (!StringUtils.hasText(rpId)) {
      throw new WhoConfigurationException(
          "who.passkey.rp-id",
          "must be set to the application's host",
          "Set it to the host passkeys are bound to, such as app.example.com. Or turn passkeys off with who.passkey.enabled=false.");
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
   * Names passkeys by email address when who-email-link is on the classpath, otherwise by identity
   * id.
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
   * Emails the owner when a passkey is added, when who-email-link and Spring's mail support are on
   * the classpath; otherwise sends nothing.
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
   * The passkey beans that use who-email-link and Spring's mail support. Its own class, loaded only
   * once the bean methods above have found those on the classpath.
   */
  private static final class PasskeyEmails {

    private PasskeyEmails() {}

    /**
     * Prefers the session's email address, then the identity's oldest one, when email-link sign-in
     * is on; otherwise the identity id.
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
        return noPasskeyAddedEmails("email-link sign-in is off (who.email-link.enabled=false)");
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
   * The notifier that sends nothing, logged once at WARN: the email is a security notification, so
   * an app that turned passkeys on should see that owners will not be told.
   */
  private static PasskeyAddedNotifier noPasskeyAddedEmails(String reason) {
    log.warn(
        "Passkey-added emails are off, so owners are not told when a passkey is added: {}", reason);
    return (identityId, label) -> {};
  }
}
