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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.passkey.JdbcWhoPasskeyCredentialRepository;
import org.jwcarman.who.passkey.JdbcWhoPasskeyUserRepository;
import org.jwcarman.who.passkey.MailPasskeyAddedNotifier;
import org.jwcarman.who.passkey.PasskeyAddedNotifier;
import org.jwcarman.who.passkey.PasskeyLoginMethod;
import org.jwcarman.who.passkey.PasskeyNameResolver;
import org.jwcarman.who.passkey.PasskeyRegistrationGuardFilter;
import org.jwcarman.who.passkey.PasskeyService;
import org.jwcarman.who.passkey.PasskeySettings;
import org.jwcarman.who.passkey.WhoRelyingPartyOperations;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Passkeys through who's autoconfiguration. Every context that turns passkeys on runs on
 * PostgreSQL, since the passkey SQL is PostgreSQL's.
 */
class PasskeyAutoConfigurationTest {

  static final String RP_ID = "who.passkey.rp-id=app.example.com";
  static final String LINK_BASE_URL = "who.email-link.link-base-url=https://app.example.com/in";

  private static final AutoConfigurations AUTO_CONFIGURATIONS =
      AutoConfigurations.of(
          DataSourceAutoConfiguration.class,
          DataSourceTransactionManagerAutoConfiguration.class,
          TransactionAutoConfiguration.class,
          JdbcTemplateAutoConfiguration.class,
          JdbcClientAutoConfiguration.class,
          MailSenderAutoConfiguration.class,
          WhoAutoConfiguration.class);

  /** Passkeys off: the embedded H2 database is enough. */
  private final ApplicationContextRunner h2Runner =
      new ApplicationContextRunner().withConfiguration(AUTO_CONFIGURATIONS);

  /** For passkeys on: PostgreSQL, with who's schema. */
  private static ApplicationContextRunner postgresRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(AUTO_CONFIGURATIONS)
        .withPropertyValues(postgresProperties());
  }

  private static String[] postgresProperties() {
    return new String[] {
      "who.initialize-schema=always",
      "spring.datasource.url=" + EmailLinkSignInTest.POSTGRES.getJdbcUrl(),
      "spring.datasource.username=" + EmailLinkSignInTest.POSTGRES.getUsername(),
      "spring.datasource.password=" + EmailLinkSignInTest.POSTGRES.getPassword()
    };
  }

  @Test
  void absentRpIdMeansNoPasskeyBeans() {
    h2Runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(PasskeySettings.class);
          assertThat(context).doesNotHaveBean(JdbcWhoPasskeyUserRepository.class);
          assertThat(context).doesNotHaveBean(JdbcWhoPasskeyCredentialRepository.class);
          assertThat(context).doesNotHaveBean(PasskeyService.class);
          assertThat(context).doesNotHaveBean(PasskeyNameResolver.class);
          assertThat(context).doesNotHaveBean(WebAuthnRelyingPartyOperations.class);
          assertThat(context).doesNotHaveBean(PasskeyLoginMethod.class);
          assertThat(context).doesNotHaveBean(PasskeyAddedNotifier.class);
          assertThat(context).doesNotHaveBean(PasskeyRegistrationGuardFilter.class);
          assertThat(context).doesNotHaveBean(AuthenticationProvider.class);
        });
  }

  @Test
  void rpIdTurnsPasskeysOn() {
    postgresRunner()
        .withPropertyValues(RP_ID)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(JdbcWhoPasskeyUserRepository.class);
              assertThat(context).hasSingleBean(JdbcWhoPasskeyCredentialRepository.class);
              assertThat(context).hasSingleBean(PasskeyService.class);
              assertThat(context).hasSingleBean(PasskeyNameResolver.class);
              assertThat(context).hasSingleBean(PasskeyAddedNotifier.class);
              assertThat(context).hasSingleBean(PasskeyLoginMethod.class);
              assertThat(context)
                  .getBean(WebAuthnRelyingPartyOperations.class)
                  .isInstanceOf(WhoRelyingPartyOperations.class);
              // built by the login method, never published as beans
              assertThat(context).doesNotHaveBean(AuthenticationProvider.class);
              assertThat(context).doesNotHaveBean(PasskeyRegistrationGuardFilter.class);
              assertThat(context.getBeansOfType(Filter.class).values())
                  .noneMatch(
                      f -> f.getClass().getPackageName().startsWith("org.jwcarman.who.passkey"));
              assertThat(context.getBean(PasskeySettings.class))
                  .isEqualTo(
                      new PasskeySettings(
                          "app.example.com",
                          "who",
                          Set.of("https://app.example.com"),
                          Duration.ofMinutes(10)));
            });
  }

  @Test
  void passkeySettingsBindFromProperties() {
    postgresRunner()
        .withPropertyValues(
            RP_ID,
            "who.passkey.rp-name=Acme",
            "who.passkey.allowed-origins=https://app.example.com,https://www.example.com",
            "who.passkey.registration-window=3m")
        .run(
            context ->
                assertThat(context.getBean(PasskeySettings.class))
                    .isEqualTo(
                        new PasskeySettings(
                            "app.example.com",
                            "Acme",
                            Set.of("https://app.example.com", "https://www.example.com"),
                            Duration.ofMinutes(3))));
  }

  @Test
  void blankRpIdFailsStartup() {
    postgresRunner()
        .withPropertyValues("who.passkey.rp-id=")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("who.passkey.rp-id"));
  }

  @Test
  void originWithoutASchemeFailsStartup() {
    postgresRunner()
        .withPropertyValues(RP_ID, "who.passkey.allowed-origins=app.example.com")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("who.passkey.allowed-origins"));
  }

  @Test
  void originWithAPathFailsStartup() {
    postgresRunner()
        .withPropertyValues(RP_ID, "who.passkey.allowed-origins=https://app.example.com/signin")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("who.passkey.allowed-origins"));
  }

  @Test
  void localDevelopmentOriginStarts() {
    postgresRunner()
        .withPropertyValues(
            "who.passkey.rp-id=localhost", "who.passkey.allowed-origins=http://localhost:8080")
        .run(
            context ->
                assertThat(context.getBean(PasskeySettings.class).allowedOrigins())
                    .containsExactly("http://localhost:8080"));
  }

  @Test
  void blankRpNameFailsStartup() {
    postgresRunner()
        .withPropertyValues(RP_ID, "who.passkey.rp-name=")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("who.passkey.rp-name"));
  }

  @Test
  void repositoriesAreTransactionalProxies() {
    postgresRunner()
        .withPropertyValues(RP_ID)
        .run(
            context -> {
              assertThat(AopUtils.isAopProxy(context.getBean(JdbcWhoPasskeyUserRepository.class)))
                  .isTrue();
              assertThat(
                      AopUtils.isAopProxy(
                          context.getBean(JdbcWhoPasskeyCredentialRepository.class)))
                  .isTrue();
            });
  }

  @Test
  void noTwoRepositoryBeansOfOneType() {
    postgresRunner()
        .withPropertyValues(RP_ID)
        .run(
            context -> {
              // Spring's WebAuthn falls back to in-memory maps when it finds two beans of a type
              assertThat(context.getBeanNamesForType(PublicKeyCredentialUserEntityRepository.class))
                  .hasSize(1);
              assertThat(context.getBeanNamesForType(UserCredentialRepository.class)).hasSize(1);
              assertThat(context.getBeanNamesForType(WebAuthnRelyingPartyOperations.class))
                  .hasSize(1);
            });
  }

  @Test
  void existingWebAuthnRelyingPartyOperationsBeanWins() {
    WebAuthnRelyingPartyOperations own = mock(WebAuthnRelyingPartyOperations.class);
    postgresRunner()
        .withPropertyValues(RP_ID)
        .withBean(WebAuthnRelyingPartyOperations.class, () -> own)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).getBean(WebAuthnRelyingPartyOperations.class).isSameAs(own);
              assertThat(context).doesNotHaveBean(WhoRelyingPartyOperations.class);
              // the provider verifies through the same relying party Spring's filters use
              assertThat(context.getBean(PasskeyLoginMethod.class))
                  .extracting("authenticationProvider")
                  .extracting("rpOperations")
                  .isSameAs(own);
            });
  }

  @Test
  void providerUsesTheRelyingPartyBean() {
    postgresRunner()
        .withPropertyValues(RP_ID)
        .run(
            context ->
                assertThat(context.getBean(PasskeyLoginMethod.class))
                    .extracting("authenticationProvider")
                    .extracting("rpOperations")
                    .isSameAs(context.getBean(WebAuthnRelyingPartyOperations.class)));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class OwnUserDetailsApp {

    @Bean
    UserDetailsService userDetailsService() {
      return new InMemoryUserDetailsManager(
          User.withUsername("alice").password("{noop}secret").roles("USER").build());
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  @Test
  void appWithItsOwnUserDetailsServiceKeepsIt() {
    new WebApplicationContextRunner()
        .withConfiguration(AUTO_CONFIGURATIONS)
        .withPropertyValues(postgresProperties())
        .withPropertyValues(RP_ID)
        .withUserConfiguration(OwnUserDetailsApp.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(UserDetailsService.class);
              assertThat(context).hasSingleBean(PasskeyLoginMethod.class);
              assertThat(context).doesNotHaveBean(AuthenticationProvider.class);
              // the global authentication manager still signs in with the app's users
              Authentication result =
                  context
                      .getBean(AuthenticationConfiguration.class)
                      .getAuthenticationManager()
                      .authenticate(
                          UsernamePasswordAuthenticationToken.unauthenticated("alice", "secret"));
              assertThat(result.isAuthenticated()).isTrue();
            });
  }

  // ---------------------------------------------------------------- names and notifications

  @Test
  void withoutEmailLinksTheNameIsTheIdentityId() {
    postgresRunner()
        .withPropertyValues(RP_ID)
        .run(
            context -> {
              UUID identityId = UUID.randomUUID();
              assertThat(
                      context
                          .getBean(PasskeyNameResolver.class)
                          .nameFor(identityId, Optional.empty()))
                  .isEqualTo(identityId.toString());
            });
  }

  @Test
  void withEmailLinksTheNameIsTheAddress() {
    postgresRunner()
        .withPropertyValues(RP_ID, LINK_BASE_URL)
        .withBean(EmailLinkSender.class, () -> (address, link) -> {})
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Identity identity =
                  context
                      .getBean(IdentityRepository.class)
                      .save(Identity.create(IdentityStatus.ACTIVE));
              String address = "passkey-name-" + identity.id() + "@example.com";
              context.getBean(EmailCredentialService.class).register(identity, address);
              assertThat(
                      context
                          .getBean(PasskeyNameResolver.class)
                          .nameFor(identity.id(), Optional.empty()))
                  .isEqualTo(address);
            });
  }

  @Test
  void mailSenderEmailLinksAndFromSendPasskeyAddedEmails() {
    postgresRunner()
        .withPropertyValues(
            RP_ID,
            LINK_BASE_URL,
            "spring.mail.host=localhost",
            "who.email-link.from=who@example.com",
            "who.passkey.from=security@example.com")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .getBean(PasskeyAddedNotifier.class)
                  .isInstanceOf(MailPasskeyAddedNotifier.class);
            });
  }

  @Test
  void withoutFromNoPasskeyAddedEmails() {
    assertThat(
            passkeyAddedEmailWarnings(
                false,
                RP_ID,
                LINK_BASE_URL,
                "spring.mail.host=localhost",
                "who.email-link.from=who@example.com"))
        .singleElement()
        .asString()
        .contains("who.passkey.from")
        .doesNotContain("who@example.com");
  }

  @Test
  void withoutEmailLinksNoPasskeyAddedEmails() {
    assertThat(
            passkeyAddedEmailWarnings(
                false,
                RP_ID,
                "spring.mail.host=localhost",
                "who.passkey.from=security@example.com"))
        .singleElement()
        .asString()
        .contains("who.email-link.link-base-url")
        .doesNotContain("security@example.com");
  }

  @Test
  void withoutAMailSenderNoPasskeyAddedEmails() {
    assertThat(
            passkeyAddedEmailWarnings(
                false, RP_ID, LINK_BASE_URL, "who.passkey.from=security@example.com"))
        .singleElement()
        .asString()
        .contains("spring.mail.host")
        .doesNotContain("security@example.com");
  }

  @Test
  void sendingPasskeyAddedEmailsWarnsNothing() {
    assertThat(
            passkeyAddedEmailWarnings(
                true,
                RP_ID,
                LINK_BASE_URL,
                "spring.mail.host=localhost",
                "who.email-link.from=who@example.com",
                "who.passkey.from=security@example.com"))
        .isEmpty();
  }

  /**
   * Starts a context whose notifier sends passkey-added emails, or not, and returns what who logged
   * about those emails, all of it at WARN.
   */
  private static List<String> passkeyAddedEmailWarnings(boolean sends, String... properties) {
    Logger logger = (Logger) LoggerFactory.getLogger(WhoAutoConfiguration.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      postgresRunner()
          // email links can then be on without a JavaMailSender
          .withBean(EmailLinkSender.class, () -> mock(EmailLinkSender.class))
          .withPropertyValues(properties)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(
                        context.getBean(PasskeyAddedNotifier.class)
                            instanceof MailPasskeyAddedNotifier)
                    .isEqualTo(sends);
              });
    } finally {
      logger.detachAppender(logs);
    }
    List<ILoggingEvent> passkeyAdded =
        logs.list.stream().filter(e -> e.getFormattedMessage().contains("Passkey-added")).toList();
    assertThat(passkeyAdded).allMatch(e -> e.getLevel() == Level.WARN);
    return passkeyAdded.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }
}
