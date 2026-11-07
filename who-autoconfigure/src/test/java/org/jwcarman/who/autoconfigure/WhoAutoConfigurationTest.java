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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailLinkLoginMethod;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.emaillink.MailEmailLinkSender;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class WhoAutoConfigurationTest {

  @Configuration
  static class TestDataSourceConfig {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    @Bean
    EmailLinkSender emailLinkSender() {
      return (address, link) -> {};
    }
  }

  /** who-email-link is on this module's test classpath, and it requires a link base URL. */
  private static final String LINK_BASE_URL =
      "who.email-link.link-base-url=https://app.example.com/signin";

  @Test
  void allModulesOnClasspathCreatesWhoServiceAndOptionalBeans() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WhoService.class);
              assertThat(context).hasSingleBean(RbacPermissionsResolver.class);
              assertThat(context).hasSingleBean(WhoJwtAuthenticationConverter.class);
              assertThat(context).hasSingleBean(WhoSessionSecurityContextRepository.class);
              assertThat(context).hasSingleBean(EmailLinkLoginMethod.class);
              assertThat(context).hasBean("whoEmailLinkExecutor");
              assertThat(context).doesNotHaveBean(MailEmailLinkSender.class);
            });
  }

  @Test
  void mailSenderIsUsedWhenSpringBootConfiguresJavaMail() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(MailSenderAutoConfiguration.class, WhoAutoConfiguration.class))
        .withUserConfiguration(MailDataSourceConfig.class)
        .withPropertyValues(
            LINK_BASE_URL, "spring.mail.host=localhost", "who.email-link.from=who@example.com")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(MailEmailLinkSender.class);
            });
  }

  @Test
  void mailSenderWithoutFromAddressFailsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(MailSenderAutoConfiguration.class, WhoAutoConfiguration.class))
        .withUserConfiguration(MailDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL, "spring.mail.host=localhost")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .hasMessageContaining("who.email-link.from"));
  }

  @Test
  void whoMeControllerIsRegisteredInServletWebApplications() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL)
        .run(context -> assertThat(context).hasSingleBean(WhoMeController.class));
  }

  @Test
  void whoMeControllerIsNotRegisteredOutsideWebApplications() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL)
        .run(context -> assertThat(context).doesNotHaveBean(WhoMeController.class));
  }

  @Configuration
  static class MailDataSourceConfig {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }
  }

  @Test
  void jdbcOnlyContextLoadsWithoutErrors() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL)
        .withClassLoader(
            new FilteredClassLoader(
                RbacPermissionsResolver.class, WhoJwtAuthenticationConverter.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WhoService.class);
              assertThat(context).doesNotHaveBean(RbacPermissionsResolver.class);
              assertThat(context).doesNotHaveBean(WhoJwtAuthenticationConverter.class);
            });
  }

  @Test
  void schemaInitializationRunsForEmbeddedDatabaseByDefault() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              JdbcClient jdbcClient = context.getBean(JdbcClient.class);
              assertThat(
                      jdbcClient
                          .sql("SELECT COUNT(*) FROM who_identity")
                          .query(Integer.class)
                          .single())
                  .isZero();
            });
  }

  @Test
  void schemaInitializationSkippedWhenModeIsNever() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL, "who.initialize-schema=never")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              JdbcClient.MappedQuerySpec<Integer> query =
                  context
                      .getBean(JdbcClient.class)
                      .sql("SELECT COUNT(*) FROM who_identity")
                      .query(Integer.class);
              assertThatThrownBy(query::single).isInstanceOf(BadSqlGrammarException.class);
            });
  }

  @Test
  void apiKeyFilterIsNotRegisteredWithTheServletContainer() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(ApiKeyAuthenticationFilter.class);
              assertThat(context)
                  .getBeans(FilterRegistrationBean.class)
                  .values()
                  .filteredOn(r -> r.getFilter() instanceof ApiKeyAuthenticationFilter)
                  .singleElement()
                  .satisfies(r -> assertThat(r.isEnabled()).isFalse());
            });
  }
}
