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

import org.jwcarman.who.core.email.WhoEmails;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.email.WhoEmailMessages;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.passkey.EmailPasskeyAddedNotifier;
import org.jwcarman.who.passkey.JdbcWhoPasskeyCredentialRepository;
import org.jwcarman.who.passkey.JdbcWhoPasskeyUserRepository;
import org.jwcarman.who.passkey.PasskeyAddedNotifier;
import org.jwcarman.who.passkey.PasskeyCredentialRemover;
import org.jwcarman.who.passkey.PasskeyLoginMethod;
import org.jwcarman.who.passkey.PasskeyNameResolver;
import org.jwcarman.who.passkey.PasskeyService;
import org.jwcarman.who.passkey.PasskeySettings;
import org.jwcarman.who.passkey.PasskeySignInCredentialCounter;
import org.jwcarman.who.passkey.WhoPasskeyAuthenticationProvider;
import org.jwcarman.who.passkey.WhoRelyingPartyOperations;
import org.jwcarman.who.spring.security.WhoPaths;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
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
@AutoConfiguration(after = {WhoAutoConfiguration.class, WhoEmailAutoConfiguration.class})
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.passkey.PasskeyLoginMethod")
@ConditionalOnBooleanProperty(name = "who.passkey.enabled", matchIfMissing = true)
public class WhoPasskeyAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(WhoPasskeyAutoConfiguration.class);

  private static final String EMAIL_CREDENTIAL_SERVICE =
      "org.jwcarman.who.emaillink.EmailCredentialService";

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoPasskeyAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * The relying party passkeys are bound to, from {@code who.passkey.*}. Startup fails without
   * {@code who.passkey.rp-id}; the allowed origins default to {@code https://} followed by the RP
   * id.
   *
   * @param properties who's properties
   * @return the settings
   */
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
        rpId, passkey.getRpName(), allowedOrigins, properties.getSecurity().getRecentSignIn());
  }

  /**
   * Who's WebAuthn user entities. Registered as a bean so its {@code @Transactional} methods are
   * proxied.
   *
   * @param jdbcClient the database
   * @param credentialIdentityRepository links credentials to identities
   * @param whoEventPublisher publishes credential events
   * @param clock stamps records and events
   * @return the repository
   */
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

  /**
   * Counts passkeys for the last-credential rule.
   *
   * @param jdbcClient the database
   * @return the counter
   */
  @Bean
  @ConditionalOnMissingBean
  public PasskeySignInCredentialCounter passkeySignInCredentialCounter(JdbcClient jdbcClient) {
    return new PasskeySignInCredentialCounter(jdbcClient);
  }

  /**
   * Who's passkey records. Registered as a bean so its {@code @Transactional} methods are proxied.
   *
   * @param jdbcClient the database
   * @param credentialIdentityRepository links credentials to identities
   * @param whoEventPublisher publishes credential events
   * @param clock stamps records and events
   * @return the repository
   */
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

  /**
   * Lists and revokes an identity's passkeys, for the application's account pages.
   *
   * @param credentialRepository who's passkey records
   * @return the service
   */
  @Bean
  @ConditionalOnMissingBean
  public PasskeyService passkeyService(JdbcWhoPasskeyCredentialRepository credentialRepository) {
    return new PasskeyService(credentialRepository);
  }

  /**
   * Names passkeys by email address when who-email-link is on the classpath, otherwise by identity
   * id.
   *
   * @param context the application, to find who-email-link's beans
   * @return the resolver
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
   * Emails the owner, through {@link WhoEmails}, when a passkey is added: when email-link sign-in
   * is on, so the owner has an address, and there is a {@link WhoEmails}. Otherwise sends nothing,
   * and says so once at WARN.
   *
   * @param context the application, to find the email beans
   * @return the notifier
   */
  @Bean
  @ConditionalOnMissingBean
  public PasskeyAddedNotifier passkeyAddedNotifier(ApplicationContext context) {
    if (!ClassUtils.isPresent(EMAIL_CREDENTIAL_SERVICE, context.getClassLoader())) {
      return noPasskeyAddedEmails("who-email-link is not present, so owners have no address");
    }
    return PasskeyEmails.notifier(context);
  }

  /**
   * The one relying party: Spring's WebAuthn filters and who's provider verify through it.
   *
   * @param settings the relying party's settings
   * @param userRepository who's WebAuthn user entities
   * @param credentialRepository who's passkey records
   * @param nameResolver names a new passkey
   * @param notifier tells the owner a passkey was added
   * @param whoEventPublisher publishes credential events
   * @param clock stamps records and events
   * @return the relying party
   */
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
   *
   * @param paths where the passkey endpoints live
   * @param userRepository who's WebAuthn user entities
   * @param credentialRepository who's passkey records
   * @param rpOperations the relying party
   * @param whoService resolves a passkey to its identity
   * @param sessionRepository stores the who session a sign-in starts
   * @param jsonMapper reads WebAuthn requests; a default one when the application has none
   * @param clock stamps events
   * @param settings the relying party's settings, for the registration window
   * @param whoEventPublisher publishes sign-in events
   * @param passkeyService lists and revokes passkeys, for {@code /me/passkeys}
   * @param lastCredentialGuard required: passkeys are never removed without the last-credential
   *     rule, so an application without it fails to start
   * @return the login method
   */
  @Bean
  @ConditionalOnMissingBean
  public PasskeyLoginMethod passkeyLoginMethod(
      WhoPaths paths,
      JdbcWhoPasskeyUserRepository userRepository,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      WebAuthnRelyingPartyOperations rpOperations,
      WhoService whoService,
      WhoSessionSecurityContextRepository sessionRepository,
      ObjectProvider<JsonMapper> jsonMapper,
      Clock clock,
      PasskeySettings settings,
      WhoEventPublisher whoEventPublisher,
      PasskeyService passkeyService,
      LastCredentialGuard lastCredentialGuard) {
    return new PasskeyLoginMethod(
        paths,
        userRepository,
        credentialRepository,
        new WhoPasskeyAuthenticationProvider(
            rpOperations, credentialRepository, whoService, whoEventPublisher, clock),
        sessionRepository,
        jsonMapper.getIfAvailable(JsonMapper::new),
        clock,
        settings.registrationWindow(),
        passkeyService,
        lastCredentialGuard);
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
     * An {@link EmailPasskeyAddedNotifier} when email-link sign-in is on and there is a {@link
     * WhoEmails}; otherwise one that sends nothing.
     */
    static PasskeyAddedNotifier notifier(ApplicationContext context) {
      EmailCredentialService emails =
          context.getBeanProvider(EmailCredentialService.class).getIfAvailable();
      if (emails == null) {
        return noPasskeyAddedEmails("email-link sign-in is off (who.email-link.enabled=false)");
      }
      WhoEmails whoEmails = context.getBeanProvider(WhoEmails.class).getIfAvailable();
      if (whoEmails == null) {
        return noPasskeyAddedEmails(
            "there is no WhoEmails: add who-email and a sender (who-email-smtp, or a"
                + " WhoEmailSender bean)");
      }
      return new EmailPasskeyAddedNotifier(
          whoEmails, identityId -> emails.findOldestFor(identityId).map(EmailCredential::address));
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

  /** The subject of the passkey-added email, when who-email renders it. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.email.WhoEmailMessages")
  static class EmailMessagesConfiguration {

    @Bean
    public WhoEmailMessages whoPasskeyMessages() {
      return new WhoEmailMessages("org/jwcarman/who/passkey/email-messages");
    }
  }
}
