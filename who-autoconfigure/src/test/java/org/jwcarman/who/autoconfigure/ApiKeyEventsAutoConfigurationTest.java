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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jwcarman.who.apikey.ApiKeyAuthenticationFilter;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.rbac.RbacService;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The API key beans the autoconfiguration makes publish through the application's {@link
 * WhoEventPublisher}, stamped by its clock, on PostgreSQL.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyEventsAutoConfigurationTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

  private final List<WhoEvent> events = new CopyOnWriteArrayList<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              WhoTestAutoConfigurations.who(
                  DataSourceAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  TransactionAutoConfiguration.class,
                  JdbcTemplateAutoConfiguration.class,
                  JdbcClientAutoConfiguration.class))
          .withPropertyValues(
              "who.initialize-schema=always",
              "spring.datasource.url=" + EmailLinkSignInTest.POSTGRES.getJdbcUrl(),
              "spring.datasource.username=" + EmailLinkSignInTest.POSTGRES.getUsername(),
              "spring.datasource.password=" + EmailLinkSignInTest.POSTGRES.getPassword(),
              "who.email-link.enabled=false",
              "who.passkey.enabled=false")
          .withBean(WhoEmailSender.class, () -> message -> {})
          .withBean(WhoEventPublisher.class, () -> events::add)
          .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void clearEvents() {
    events.clear();
  }

  /** signIn runs the filter outside a chain, so nothing else clears what it signed in. */
  @AfterEach
  void clearSignIn() {
    SecurityContextHolder.clearContext();
  }

  private static int signIn(AssertableApplicationContext context, String key) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-API-Key", key);
    MockHttpServletResponse response = new MockHttpServletResponse();
    context
        .getBean(ApiKeyAuthenticationFilter.class)
        .doFilter(request, response, new MockFilterChain());
    return response.getStatus();
  }

  @Nested
  class The_autoconfigured_beans {

    @Test
    void publish_creation_and_revocation_of_a_key_but_not_its_sign_in() {
      runner.run(
          context -> {
            Identity identity = context.getBean(WhoService.class).createIdentity();
            ApiKeyService apiKeys = context.getBean(ApiKeyService.class);
            TestApiKeys.grant(context.getBean(RbacService.class), identity);
            events.clear();

            String key = TestApiKeys.create(apiKeys, identity, "Laptop");
            CredentialAdded added = (CredentialAdded) events.getFirst();
            UUID credentialId = added.credentialId();
            assertThat(signIn(context, key)).isEqualTo(200);
            apiKeys.revoke(credentialId);

            assertThat(events)
                .containsExactly(
                    new CredentialAdded(
                        NOW, identity.id(), credentialId, CredentialKind.API_KEY, "Laptop"),
                    new CredentialRevoked(
                        NOW, identity.id(), credentialId, CredentialKind.API_KEY, "Laptop"));
          });
    }

    @Test
    void deleting_an_identity_reports_its_key_as_an_api_key() {
      runner.run(
          context -> {
            WhoService whoService = context.getBean(WhoService.class);
            Identity identity = whoService.createIdentity();
            TestApiKeys.grant(context.getBean(RbacService.class), identity);
            TestApiKeys.create(context.getBean(ApiKeyService.class), identity, "Laptop");
            UUID credentialId = ((CredentialAdded) events.getLast()).credentialId();
            events.clear();

            assertThat(whoService.deleteIdentity(identity.id())).isTrue();

            assertThat(events)
                .contains(
                    new CredentialRevoked(
                        NOW, identity.id(), credentialId, CredentialKind.API_KEY, "Laptop"));
          });
    }
  }

  @ParameterizedTest
  @CsvSource({
    "who.api-key.prefix=who, who.api-key.prefix",
    "who.api-key.prefix=x-y_, who.api-key.prefix",
    "who.api-key.max-lifetime=0s, who.api-key.max-lifetime",
    "who.api-key.max-lifetime=-1d, who.api-key.max-lifetime",
    "who.api-key.last-used-interval=0s, who.api-key.last-used-interval",
    "who.api-key.last-used-interval=-5m, who.api-key.last-used-interval"
  })
  void a_bad_api_key_setting_fails_startup_naming_it(String setting, String name) {
    runner
        .withPropertyValues(setting)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("IllegalStateException: " + name));
  }
}
