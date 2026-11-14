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

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.emaillink.EmailLinkIssuer;
import org.jwcarman.who.emaillink.EmailLinkLoginMethod;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.enrollment.WhoEnrollmentService;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.passkey.PasskeyLoginMethod;
import org.jwcarman.who.passkey.PasskeyService;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

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
  }

  @Configuration(proxyBeanMethods = false)
  static class Sender {
    @Bean
    EmailLinkSender emailLinkSender() {
      return message -> {};
    }
  }
}
