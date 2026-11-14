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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyCredentialRepository;
import org.jwcarman.who.apikey.JdbcApiKeyCredentialRepository;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredentialRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkIssuer;
import org.jwcarman.who.emaillink.EmailLinkLoginMethod;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.emaillink.JdbcEmailCredentialRepository;
import org.jwcarman.who.emaillink.MailEmailLinkSender;
import org.jwcarman.who.jwt.JdbcJwtCredentialRepository;
import org.jwcarman.who.jwt.JwtCredentialRepository;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.jwcarman.who.rbac.RbacPermissionsResolver;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

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
  }

  /** Opts in to email-link sign-in. */
  private static final String LINK_BASE_URL =
      "who.email-link.link-base-url=https://app.example.com/signin";

  private static final String CODE_SECRET =
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij";

  @Test
  void whoServiceObservesResolutionsWithTheApplicationsObservationRegistry() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withBean(ObservationRegistry.class, () -> observations)
        .withBean(IdentityRepository.class, () -> mock(IdentityRepository.class))
        .withBean(
            CredentialIdentityRepository.class, () -> mock(CredentialIdentityRepository.class))
        .run(
            context -> {
              UUID credentialId = UUID.randomUUID();
              context.getBean(WhoService.class).resolveDetailed(() -> credentialId);

              TestObservationRegistryAssert.assertThat(observations)
                  .hasObservationWithNameEqualTo(WhoService.RESOLVE_OBSERVATION)
                  .that()
                  .hasLowCardinalityKeyValue("outcome", "not-linked");
            });
  }

  @Test
  void whoServiceResolvesWithoutAnObservationRegistry() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withBean(IdentityRepository.class, () -> mock(IdentityRepository.class))
        .withBean(
            CredentialIdentityRepository.class, () -> mock(CredentialIdentityRepository.class))
        .run(
            context -> {
              UUID credentialId = UUID.randomUUID();
              assertThat(context).doesNotHaveBean(ObservationRegistry.class);
              assertThat(context.getBean(WhoService.class).resolveDetailed(() -> credentialId))
                  .isInstanceOf(Resolution.NotLinked.class);
            });
  }

  @Test
  void allModulesOnClasspathCreatesWhoServiceAndOptionalBeans() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WhoService.class);
              assertThat(context).hasSingleBean(RbacPermissionsResolver.class);
              assertThat(context).hasSingleBean(WhoJwtAuthenticationConverter.class);
              assertThat(context).hasSingleBean(WhoSessionSecurityContextRepository.class);
              assertThat(context).doesNotHaveBean(EmailLinkLoginMethod.class);
            });
  }

  @Test
  void appWithItsOwnMailButNoEmailLinksIsAskedForNothing() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(MailSenderAutoConfiguration.class, WhoAutoConfiguration.class))
        .withUserConfiguration(MailDataSourceConfig.class)
        .withPropertyValues("spring.mail.host=localhost")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MailEmailLinkSender.class);
              assertThat(context).doesNotHaveBean(EmailLinkLoginMethod.class);
            });
  }

  @Test
  void emailLinkIssuerObservesWithTheApplicationsObservationRegistry() {
    TestObservationRegistry observations = TestObservationRegistry.create();
    EmailCredentialService emailCredentials = mock(EmailCredentialService.class);
    when(emailCredentials.findByAddress("nobody@example.com")).thenReturn(Optional.empty());
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(MailDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL, CODE_SECRET)
        .withBean(ObservationRegistry.class, () -> observations)
        .withBean(EmailCredentialService.class, () -> emailCredentials)
        .withBean(EmailLinkSender.class, () -> mock(EmailLinkSender.class))
        .run(
            context -> {
              context.getBean(EmailLinkIssuer.class).issue("nobody@example.com", "token");

              TestObservationRegistryAssert.assertThat(observations)
                  .hasObservationWithNameEqualTo(EmailLinkIssuer.ISSUE_OBSERVATION)
                  .that()
                  .hasLowCardinalityKeyValue("outcome", "unknown-address");
            });
  }

  @Test
  void mailSenderIsUsedWhenSpringBootConfiguresJavaMail() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(MailSenderAutoConfiguration.class, WhoAutoConfiguration.class))
        .withUserConfiguration(MailDataSourceConfig.class)
        .withPropertyValues(
            LINK_BASE_URL,
            CODE_SECRET,
            "spring.mail.host=localhost",
            "who.email-link.from=who@example.com")
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
        .withPropertyValues(LINK_BASE_URL, CODE_SECRET, "spring.mail.host=localhost")
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
        .run(context -> assertThat(context).hasSingleBean(WhoMeController.class));
  }

  @Test
  void exceptionHandoffSendsTheFallbackWhenMvcDeclinesTheException() {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class, DecliningResolverConfig.class)
        .run(
            context -> {
              MockHttpServletResponse response = new MockHttpServletResponse();
              context
                  .getBean(WhoExceptionHandoff.class)
                  .handOff(
                      new MockHttpServletRequest(),
                      response,
                      new IllegalStateException("unhandled"),
                      HttpStatus.UNAUTHORIZED);
              assertThat(response.getStatus()).isEqualTo(401);
            });
  }

  /** An MVC resolver that handles nothing, as when no advice matches the exception. */
  @Configuration
  static class DecliningResolverConfig {
    @Bean
    HandlerExceptionResolver handlerExceptionResolver() {
      return (request, response, handler, exception) -> null;
    }
  }

  @Test
  void whoMeControllerIsNotRegisteredOutsideWebApplications() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
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
        .withClassLoader(
            new FilteredClassLoader(
                RbacPermissionsResolver.class, WhoJwtAuthenticationConverter.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(WhoService.class);
              assertThat(context).doesNotHaveBean(RbacPermissionsResolver.class);
              assertThat(context).doesNotHaveBean(WhoJwtAuthenticationConverter.class);
              // without who-rbac, no identity has permissions
              assertThat(context.getBean(PermissionsResolver.class).resolve(Identity.create()))
                  .isEmpty();
            });
  }

  @Test
  void schemaInitializationRunsForEmbeddedDatabaseByDefault() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
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
        .withPropertyValues("who.initialize-schema=never")
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

  @Test
  void everyPresentCredentialModuleRegistersItsRepositoryAsItsRemover() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withPropertyValues(LINK_BASE_URL, CODE_SECRET)
        .withBean(EmailLinkSender.class, () -> message -> {})
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              List<Object> removers =
                  List.copyOf(context.getBeansOfType(CredentialRemover.class).values());
              assertThat(removers)
                  .containsExactlyInAnyOrder(
                      context.getBean(EmailCredentialRepository.class),
                      context.getBean(JwtCredentialRepository.class),
                      context.getBean(ApiKeyCredentialRepository.class));
              assertThat(context.getBean(EmailCredentialRepository.class))
                  .isInstanceOf(JdbcEmailCredentialRepository.class);
              assertThat(context.getBean(JwtCredentialRepository.class))
                  .isInstanceOf(JdbcJwtCredentialRepository.class);
              assertThat(context.getBean(ApiKeyCredentialRepository.class))
                  .isInstanceOf(JdbcApiKeyCredentialRepository.class);
            });
  }

  @Test
  void withoutEmailLinksThereIsNoEmailRemover() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .run(
            context ->
                assertThat(context.getBeansOfType(CredentialRemover.class).values())
                    .hasSize(2)
                    .noneMatch(JdbcEmailCredentialRepository.class::isInstance));
  }

  @Test
  void whoServiceUsesEveryCredentialRemover() {
    CredentialRemover own = mock(CredentialRemover.class);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(TestDataSourceConfig.class)
        .withBean("ownCredentialRemover", CredentialRemover.class, () -> own)
        .run(
            context -> {
              context.getBean(WhoService.class).purgeOrphanedCredentials();
              verify(own).removeUnlinked();
            });
  }
}
