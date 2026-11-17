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

import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.admin.IdentityEmails;
import org.jwcarman.who.admin.LastAdminGuard;
import org.jwcarman.who.admin.WhoAdminEndpoints;
import org.jwcarman.who.admin.WhoInvitationEndpoints;
import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.signup.ClosedSignUpPolicy;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailLinkIssuer;
import org.jwcarman.who.emaillink.EmailLinkLoginMethod;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.emaillink.InvitationRoles;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.emaillink.RbacInvitationRoles;
import org.jwcarman.who.enrollment.WhoEnrollmentService;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.oidc.OidcLoginMethod;
import org.jwcarman.who.oidc.OidcSignUpService;
import org.jwcarman.who.oidc.SignInRegistrations;
import org.jwcarman.who.passkey.PasskeyLoginMethod;
import org.jwcarman.who.passkey.PasskeyService;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A module's jar turns it on; {@code who.<module>.enabled=false} is the only way to turn it off.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ModuleActivationTest {

  private static final String LINK = "who.email-link.link-base-url=https://app.example.com/signin";
  private static final String SECRET = "who.email-link.code-secret=" + "s".repeat(32);
  private static final String RP_ID = "who.passkey.rp-id=app.example.com";

  /** Every module on the classpath, each with what it needs. */
  private final ApplicationContextRunner configured =
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class, Sender.class)
          .withPropertyValues(LINK, SECRET, RP_ID);

  @Nested
  class Email_link {

    @Test
    void is_on_with_the_jar_alone() {
      configured.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmailLinkIssuer.class);
            assertThat(context).hasSingleBean(EmailLinkLoginMethod.class);
          });
    }

    @Test
    void is_off_with_enabled_false() {
      configured
          .withPropertyValues("who.email-link.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(EmailLinkIssuer.class);
                assertThat(context).doesNotHaveBean(EmailLinkLoginMethod.class);
                assertThat(context).doesNotHaveBean("whoEmailLinkExecutor");
              });
    }

    @Test
    void fails_without_link_base_url() {
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class, Sender.class)
          .withPropertyValues(SECRET, RP_ID)
          .run(context -> assertMissing(context, "who.email-link.link-base-url", "email-link"));
    }

    @Test
    void fails_without_code_secret() {
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class, Sender.class)
          .withPropertyValues(LINK, RP_ID)
          .run(context -> assertMissing(context, "who.email-link.code-secret", "email-link"));
    }

    @Test
    void fails_without_a_sender() {
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class)
          .withPropertyValues(LINK, SECRET, RP_ID)
          .run(context -> assertMissing(context, "spring.mail.host", "email-link"));
    }

    @Test
    void without_the_mail_starter_the_failure_names_it() {
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withClassLoader(new FilteredClassLoader("org.springframework.mail"))
          .withUserConfiguration(Database.class)
          .withPropertyValues(
              LINK,
              SECRET,
              RP_ID,
              "spring.mail.host=smtp.example.com",
              "who.email-link.from=sign-in@example.com")
          .run(
              context -> {
                assertMissing(context, "spring.mail.host", "email-link");
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOfSatisfying(
                        WhoConfigurationException.class,
                        e -> assertThat(e.action()).contains("spring-boot-starter-mail"));
              });
    }
  }

  @Nested
  class Passkey {

    @Test
    void is_on_with_the_jar_alone() {
      configured.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PasskeyService.class);
            assertThat(context).hasSingleBean(PasskeyLoginMethod.class);
          });
    }

    @Test
    void is_off_with_enabled_false() {
      configured
          .withPropertyValues("who.passkey.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(PasskeyService.class);
                assertThat(context).doesNotHaveBean(PasskeyLoginMethod.class);
              });
    }

    @Test
    void fails_without_rp_id() {
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class, Sender.class)
          .withPropertyValues(LINK, SECRET)
          .run(context -> assertMissing(context, "who.passkey.rp-id", "passkey"));
    }

    @Test
    void passkey_starts_with_email_link_turned_off() {
      configured
          .withPropertyValues("who.email-link.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(PasskeyService.class);
              });
    }
  }

  @Nested
  class Api_key {

    @Test
    void is_on_with_the_jar_alone() {
      configured.run(
          context -> assertThat(context).hasSingleBean(ApiKeyAuthenticationFilter.class));
    }

    @Test
    void is_off_with_enabled_false() {
      configured
          .withPropertyValues("who.api-key.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ApiKeyAuthenticationFilter.class);
              });
    }
  }

  @Nested
  class Invitations {

    @Test
    void are_on_with_email_sign_in_and_give_roles_with_rbac() {
      configured.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InvitationService.class);
            assertThat(context).hasSingleBean(RbacInvitationRoles.class);
          });
    }

    @Test
    void without_rbac_invite_with_no_roles() {
      configured
          .withClassLoader(
              new FilteredClassLoader(RbacPermissionsResolver.class, RbacService.class))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(InvitationService.class);
                assertThat(context).doesNotHaveBean(InvitationRoles.class);
                assertThat(context).doesNotHaveBean(WhoInvitationEndpoints.class);
              });
    }

    @Test
    void are_off_without_email_sign_in() {
      configured
          .withPropertyValues("who.email-link.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(InvitationService.class);
                assertThat(context).doesNotHaveBean(InvitationRoles.class);
              });
    }
  }

  @Nested
  class Admin {

    @Test
    void is_on_with_the_jar_alone() {
      configured.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WhoAdminEndpoints.class);
            assertThat(context).hasSingleBean(IdentityEmails.class);
            assertThat(context).hasSingleBean(WhoInvitationEndpoints.class);
          });
    }

    @Test
    void without_email_sign_in_it_finds_no_addresses() {
      configured
          .withPropertyValues("who.email-link.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(WhoAdminEndpoints.class);
                assertThat(context).doesNotHaveBean(IdentityEmails.class);
                assertThat(context).doesNotHaveBean(WhoInvitationEndpoints.class);
              });
    }

    @Test
    void is_off_with_enabled_false() {
      configured
          .withPropertyValues("who.admin.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WhoAdminEndpoints.class);
                assertThat(context).doesNotHaveBean(IdentityEmails.class);
                assertThat(context).doesNotHaveBean(WhoInvitationEndpoints.class);
                assertThat(context).hasSingleBean(InvitationService.class);
              });
    }

    @Test
    void the_last_admin_rule_enforces_with_who_rbac_permissions() {
      configured.run(
          context -> assertThat(context.getBean(LastAdminGuard.class).isEnforcing()).isTrue());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void the_last_admin_rule_is_off_and_says_so_with_permissions_of_the_apps_own(
        CapturedOutput output) {
      configured
          .withUserConfiguration(AppPermissions.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(LastAdminGuard.class).isEnforcing()).isFalse();
              });
      assertThat(output).contains("who: the last-admin rule is off");
    }

    @Test
    void two_transaction_managers_and_no_primary_fail_startup_naming_the_last_admin_guard() {
      configured
          // nothing else here needs the last-credential guard
          .withPropertyValues("who.email-link.enabled=false", "who.passkey.enabled=false")
          .withUserConfiguration(SecondTransactionManager.class)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(Last_credential_guard.messages(context.getStartupFailure()))
                    .contains("LastAdminGuard");
              });
    }
  }

  @Nested
  class Last_sign_in_cookie {

    @Test
    void is_on_by_default() {
      configured.run(context -> assertThat(context).hasSingleBean(LastSignInCookie.class));
    }

    @Test
    void the_cookie_can_be_turned_off() {
      configured
          .withPropertyValues("who.security.last-sign-in-cookie.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(LastSignInCookie.class);
              });
    }
  }

  @Nested
  class Jwt {

    @Test
    void is_on_with_the_jar_alone() {
      configured.run(
          context -> assertThat(context).hasSingleBean(WhoJwtAuthenticationConverter.class));
    }

    @Test
    void is_off_with_enabled_false() {
      configured
          .withPropertyValues("who.jwt.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WhoJwtAuthenticationConverter.class);
              });
    }
  }

  @Nested
  class Enrollment {

    @Test
    void is_on_with_the_jar_alone() {
      configured.run(context -> assertThat(context).hasSingleBean(WhoEnrollmentService.class));
    }

    @Test
    void is_off_with_enabled_false() {
      configured
          .withPropertyValues("who.enrollment.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WhoEnrollmentService.class);
              });
    }
  }

  @Nested
  @ExtendWith(OutputCaptureExtension.class)
  class Oidc {

    private static final String GITHUB = "spring.security.oauth2.client.registration.github.";

    private final ApplicationContextRunner oidc =
        configured
            .withConfiguration(
                AutoConfigurations.of(
                    OAuth2ClientAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues(
                "who.oidc.enabled=true",
                "who.security.public-paths=/",
                GITHUB + "client-id=client",
                GITHUB + "client-secret=secret");

    @Test
    void is_on_with_the_jar_and_a_registration() {
      oidc.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OidcLoginMethod.class);
            assertThat(context).hasSingleBean(OidcSignUpService.class);
            assertThat(context.getBean(WhoSignUpPolicy.class))
                .isInstanceOf(ClosedSignUpPolicy.class);
          });
    }

    @Test
    void is_off_with_enabled_false() {
      oidc.withPropertyValues("who.oidc.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(OidcLoginMethod.class);
                assertThat(context).doesNotHaveBean(SignInRegistrations.class);
                assertThat(context).doesNotHaveBean(WhoSignUpPolicy.class);
              });
    }

    @Test
    void no_registration_fails_startup_naming_the_property() {
      configured
          .withConfiguration(
              AutoConfigurations.of(
                  OAuth2ClientAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class))
          .withPropertyValues("who.oidc.enabled=true")
          .run(
              context ->
                  assertMissing(context, "spring.security.oauth2.client.registration", "oidc"));
    }

    @Test
    void missing_issuer_fails_startup() {
      String acme = "spring.security.oauth2.client.registration.acme.";
      String provider = "spring.security.oauth2.client.provider.acme.";
      oidc.withPropertyValues(
              acme + "client-id=client",
              acme + "client-secret=secret",
              acme + "scope=openid",
              acme + "authorization-grant-type=authorization_code",
              acme + "redirect-uri={baseUrl}/who/oidc/callback/{registrationId}",
              provider + "authorization-uri=https://idp.example.com/authorize",
              provider + "token-uri=https://idp.example.com/token",
              provider + "jwk-set-uri=https://idp.example.com/jwks")
          .run(context -> assertMissing(context, "who.oidc.providers.acme.issuer", "oidc"));
    }

    @Test
    void duplicate_issuer_fails_startup() {
      String gh = "spring.security.oauth2.client.registration.gh.";
      oidc.withPropertyValues(
              gh + "client-id=client", gh + "client-secret=secret", gh + "provider=github")
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .rootCause()
                      .isInstanceOfSatisfying(
                          WhoConfigurationException.class,
                          e -> {
                            // whichever of the two Spring Boot lists second
                            assertThat(e.property())
                                .isIn(
                                    "who.oidc.providers.gh.issuer",
                                    "who.oidc.providers.github.issuer");
                            assertThat(e.getMessage()).contains("the same issuer");
                            assertThat(e.action()).contains("who.oidc.enabled=false");
                          }));
    }

    @Test
    void absolute_link_url_fails_startup() {
      oidc.withPropertyValues("who.oidc.link-url=https://evil.example.com/")
          .run(context -> assertMissing(context, "who.oidc.link-url", "oidc"));
    }

    @Test
    void absolute_failure_url_fails_startup() {
      oidc.withPropertyValues("who.oidc.failure-url=https://evil.example.com/")
          .run(context -> assertMissing(context, "who.oidc.failure-url", "oidc"));
    }

    @Test
    void failure_url_not_public_warns(CapturedOutput output) {
      oidc.withPropertyValues("who.oidc.failure-url=/signin?x=1")
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(output)
          .contains(
              "who.oidc.failure-url /signin?x=1 is not in who.security.public-paths; a"
                  + " signed-out person sent there gets a 401");
    }

    @Test
    void failure_url_that_is_public_does_not_warn(CapturedOutput output) {
      oidc.withPropertyValues("who.oidc.failure-url=/signin", "who.security.public-paths=/signin")
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(output).doesNotContain("is not in who.security.public-paths");
    }
  }

  @Nested
  class Last_credential_guard {

    private static final String GITHUB = "spring.security.oauth2.client.registration.github.";

    /** Passkeys, email links and OIDC all on: each one's removal endpoint needs the guard. */
    private final ApplicationContextRunner everyCredential =
        configured
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withPropertyValues(
                "who.oidc.enabled=true",
                "who.security.public-paths=/",
                GITHUB + "client-id=client",
                GITHUB + "client-secret=secret");

    @Test
    void two_transaction_managers_and_no_primary_fail_startup_naming_the_guard() {
      everyCredential
          .withUserConfiguration(SecondTransactionManager.class)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(messages(context.getStartupFailure())).contains("LastCredentialGuard");
              });
    }

    @Test
    void a_primary_transaction_manager_among_two_starts() {
      everyCredential
          .withUserConfiguration(PrimaryTransactionManager.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LastCredentialGuard.class);
              });
    }

    /** Every message in the failure's cause chain. */
    static String messages(Throwable failure) {
      StringBuilder messages = new StringBuilder();
      for (Throwable t = failure; t != null; t = t.getCause()) {
        messages.append(t.getMessage()).append('\n');
      }
      return messages.toString();
    }
  }

  /** The failure names the property and offers the module's off switch. */
  private static void assertMissing(
      AssertableApplicationContext context, String property, String module) {
    assertThat(context.getStartupFailure())
        .isNotNull()
        .rootCause()
        .isInstanceOfSatisfying(
            WhoConfigurationException.class,
            e -> {
              assertThat(e.property()).isEqualTo(property);
              assertThat(e.action()).contains("who." + module + ".enabled=false");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Database {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    /** What the last-credential guard, which passkeys require, runs its transaction on. */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  /** The application's own permissions, in place of who-rbac's. */
  @Configuration(proxyBeanMethods = false)
  static class AppPermissions {
    @Bean
    @Primary
    PermissionsResolver appPermissions() {
      return identity -> Set.of();
    }
  }

  /** A second transaction manager beside {@link Database}'s, with neither primary. */
  @Configuration(proxyBeanMethods = false)
  static class SecondTransactionManager {
    @Bean
    PlatformTransactionManager otherTransactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  /** A second transaction manager beside {@link Database}'s, marked primary. */
  @Configuration(proxyBeanMethods = false)
  static class PrimaryTransactionManager {
    @Bean
    @Primary
    PlatformTransactionManager otherTransactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Sender {
    @Bean
    EmailLinkSender emailLinkSender() {
      return message -> {};
    }
  }
}
