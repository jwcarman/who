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

import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpSessionEvent;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionEndListener;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSessionsConfigurationTest {

  @Configuration(proxyBeanMethods = false)
  static class Database {
    @Bean
    DataSource dataSource() {
      return WhoTestPostgres.newDataSource();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(Database.class)
          .withPropertyValues(
              "who.email-link.enabled=false",
              "who.passkey.enabled=false",
              "who.admin.enabled=false",
              "migratory.enabled=false");

  @Test
  void the_default_timeout_is_thirty_minutes() {
    runner.run(
        context -> {
          WhoSessions sessions = context.getBean(WhoSessions.class);
          assertThat(sessions.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
          assertThat(sessions.lastSeenInterval()).isEqualTo(Duration.ofMinutes(5));
          assertThat(sessions.purgeBatchSize()).isEqualTo(100);
          assertThat(sessions.autoPurge()).isTrue();
        });
  }

  @Test
  void sessions_use_the_servlet_timeout() {
    runner
        .withPropertyValues("server.servlet.session.timeout=45m")
        .run(
            context ->
                assertThat(context.getBean(WhoSessions.class).idleTimeout())
                    .isEqualTo(Duration.ofMinutes(45)));
  }

  @Test
  void spring_session_timeout_wins() {
    runner
        .withPropertyValues("server.servlet.session.timeout=45m", "spring.session.timeout=2h")
        .run(
            context ->
                assertThat(context.getBean(WhoSessions.class).idleTimeout())
                    .isEqualTo(Duration.ofHours(2)));
  }

  @Test
  void a_timeout_without_a_unit_is_seconds_as_in_spring_boot() {
    runner
        .withPropertyValues("server.servlet.session.timeout=1800")
        .run(
            context ->
                assertThat(context.getBean(WhoSessions.class).idleTimeout())
                    .isEqualTo(Duration.ofMinutes(30)));
    runner
        .withPropertyValues("spring.session.timeout=3600")
        .run(
            context ->
                assertThat(context.getBean(WhoSessions.class).idleTimeout())
                    .isEqualTo(Duration.ofHours(1)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0s"})
  void a_session_that_never_expires_fails_startup_with_a_reason(String timeout) {
    runner
        .withPropertyValues("server.servlet.session.timeout=" + timeout)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("server.servlet.session.timeout")
                    .hasMessageContaining("positive"));
  }

  @Test
  void an_empty_timeout_keeps_spring_boots_default() {
    // as server.servlet.session.timeout=${SESSION_TIMEOUT:} with the variable unset
    runner
        .withPropertyValues("server.servlet.session.timeout=")
        .run(
            context ->
                assertThat(context.getBean(WhoSessions.class).idleTimeout())
                    .isEqualTo(Duration.ofMinutes(30)));
  }

  @Test
  void a_malformed_timeout_fails_startup_naming_the_property() {
    runner
        .withPropertyValues("server.servlet.session.timeout=soon")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(
                        "server.servlet.session.timeout=soon is not a duration"));
  }

  @Test
  void spring_session_timeout_is_ignored_without_spring_session() {
    // Spring Boot reads it only for Spring Session; without it the container's timeout applies
    runner
        .withClassLoader(new FilteredClassLoader("org.springframework.session"))
        .withPropertyValues("server.servlet.session.timeout=45m", "spring.session.timeout=5m")
        .run(
            context ->
                assertThat(context.getBean(WhoSessions.class).idleTimeout())
                    .isEqualTo(Duration.ofMinutes(45)));
  }

  @Test
  void auto_purge_can_be_turned_off() {
    runner
        .withPropertyValues("who.sessions.auto-purge=false")
        .run(context -> assertThat(context.getBean(WhoSessions.class).autoPurge()).isFalse());
  }

  @Test
  void batch_size_and_interval_bind() {
    runner
        .withPropertyValues(
            "who.sessions.purge-batch-size=25", "who.sessions.last-seen-interval=1m")
        .run(
            context -> {
              WhoSessions sessions = context.getBean(WhoSessions.class);
              assertThat(sessions.purgeBatchSize()).isEqualTo(25);
              assertThat(sessions.lastSeenInterval()).isEqualTo(Duration.ofMinutes(1));
            });
  }

  @Test
  void a_destroyed_servlet_session_closes_its_row() {
    runner
        .withPropertyValues("migratory.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(WhoSessionSecurityContextRepository.class);
              WhoSessions sessions = context.getBean(WhoSessions.class);
              Identity identity =
                  context
                      .getBean(IdentityRepository.class)
                      .save(Identity.create(IdentityStatus.ACTIVE));
              UUID credentialId = UUID.randomUUID();
              SessionRecord row =
                  sessions.start(identity.id(), credentialId, SignInMethod.PASSKEY, null);
              MockHttpSession session = new MockHttpSession();
              session.setAttribute(
                  WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
                  new WhoSession(identity.id(), credentialId, row.signedInAt(), null, row.id()));

              context
                  .getBean(WhoSessionEndListener.class)
                  .sessionDestroyed(new HttpSessionEvent(session));

              assertThat(sessions.findLive(row.id())).isEmpty();
            });
  }
}
