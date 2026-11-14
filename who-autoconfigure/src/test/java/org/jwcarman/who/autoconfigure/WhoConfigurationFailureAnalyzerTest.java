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
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoConfigurationFailureAnalyzerTest {

  private static final String LINK = "who.email-link.link-base-url=https://app.example.com/signin";
  private static final String SECRET = "who.email-link.code-secret=" + "s".repeat(32);

  @Test
  void describes_the_property_and_says_what_to_do() {
    FailureAnalysis analysis =
        new WhoConfigurationFailureAnalyzer()
            .analyze(
                new WhoConfigurationException(
                    "who.passkey.rp-id",
                    "must be set",
                    "Set it to the application's host, such as app.example.com."));

    assertThat(analysis.getDescription())
        .isEqualTo("who cannot start: who.passkey.rp-id must be set.");
    assertThat(analysis.getAction())
        .isEqualTo("Set it to the application's host, such as app.example.com.");
  }

  @Test
  void the_message_names_the_property_and_says_what_to_do() {
    assertThat(new WhoConfigurationException("who.x", "must be set", "Set it."))
        .hasMessage("who.x must be set. Set it.")
        .isInstanceOf(IllegalStateException.class);
  }

  @Nested
  class Startup_fails_with_the_property_for {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(WhoTestAutoConfigurations.who())
            .withUserConfiguration(Database.class);

    @Test
    void a_blank_link_base_url() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues("who.email-link.link-base-url=", SECRET)
          .run(context -> assertProperty(context, "who.email-link.link-base-url"));
    }

    @Test
    void a_short_code_secret() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues(LINK, "who.email-link.code-secret=short")
          .run(context -> assertProperty(context, "who.email-link.code-secret"));
    }

    @Test
    void an_unresolved_code_secret() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues(LINK, "who.email-link.code-secret=${NOT_SET_ANYWHERE_AT_ALL_REALLY}")
          .run(context -> assertProperty(context, "who.email-link.code-secret"));
    }

    @Test
    void a_short_code_length() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues(LINK, SECRET, "who.email-link.code-length=5")
          .run(context -> assertProperty(context, "who.email-link.code-length"));
    }

    @Test
    void no_code_attempts() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues(LINK, SECRET, "who.email-link.code-max-attempts=0")
          .run(context -> assertProperty(context, "who.email-link.code-max-attempts"));
    }

    @Test
    void a_resend_interval_longer_than_the_link() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues(
              LINK, SECRET, "who.email-link.token-ttl=1m", "who.email-link.resend-interval=2m")
          .run(context -> assertProperty(context, "who.email-link.resend-interval"));
    }

    @Test
    void no_way_to_send_sign_in_emails() {
      runner
          .withPropertyValues(LINK, SECRET)
          .run(context -> assertProperty(context, "spring.mail.host"));
    }

    @Test
    void a_blank_rp_id() {
      runner
          .withUserConfiguration(Sender.class)
          .withPropertyValues(LINK, SECRET, "who.passkey.rp-id=")
          .run(context -> assertProperty(context, "who.passkey.rp-id"));
    }
  }

  private static void assertProperty(AssertableApplicationContext context, String property) {
    assertThat(context.getStartupFailure())
        .isNotNull()
        .rootCause()
        .isInstanceOfSatisfying(
            WhoConfigurationException.class, e -> assertThat(e.property()).isEqualTo(property));
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
