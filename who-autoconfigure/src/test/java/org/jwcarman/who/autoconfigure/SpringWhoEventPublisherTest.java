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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.IdentityCreated;
import org.jwcarman.who.core.event.IdentityDeactivated;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Who's events reach Spring listeners only after the transaction that caused them commits. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SpringWhoEventPublisherTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataSourceAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  TransactionAutoConfiguration.class,
                  JdbcTemplateAutoConfiguration.class,
                  JdbcClientAutoConfiguration.class,
                  WhoAutoConfiguration.class))
          .withUserConfiguration(Listener.class)
          .withPropertyValues(
              "who.initialize-schema=always",
              "who.email-link.link-base-url=https://app.example.com/signin",
              "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
              "spring.datasource.url=" + EmailLinkSignInTest.POSTGRES.getJdbcUrl(),
              "spring.datasource.username=" + EmailLinkSignInTest.POSTGRES.getUsername(),
              "spring.datasource.password=" + EmailLinkSignInTest.POSTGRES.getPassword())
          .withBean(EmailLinkSender.class, () -> message -> {});

  /** Records every event and can be told to fail. */
  @Configuration(proxyBeanMethods = false)
  static class Listener {
    final List<WhoEvent> events = new ArrayList<>();
    boolean failing;

    @EventListener
    void on(WhoEvent event) {
      events.add(event);
      if (failing) {
        throw new IllegalStateException("secret-detail");
      }
    }
  }

  /** An audit listener that writes in a transaction of its own, as the events guide advises. */
  @Configuration(proxyBeanMethods = false)
  static class RequiresNewAudit {
    private final JdbcClient jdbc;

    RequiresNewAudit(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(IdentityDeactivated event) {
      jdbc.sql("INSERT INTO audit_requires_new (identity_id) VALUES (:id)")
          .param("id", event.identityId())
          .update();
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Database_listeners {

    @Test
    void a_requires_new_listener_write_after_commit_is_visible_afterwards() {
      runner
          .withUserConfiguration(RequiresNewAudit.class)
          .run(
              context -> {
                JdbcClient jdbc = context.getBean(JdbcClient.class);
                jdbc.sql("CREATE TABLE audit_requires_new (identity_id uuid NOT NULL)").update();
                WhoService whoService = context.getBean(WhoService.class);
                UUID id = whoService.createIdentity().id();
                TransactionTemplate tx =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

                tx.executeWithoutResult(status -> assertThat(whoService.deactivate(id)).isTrue());

                assertThat(
                        jdbc.sql("SELECT COUNT(*) FROM audit_requires_new WHERE identity_id = :id")
                            .param("id", id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
              });
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Publishing {

    @Test
    void outside_a_transaction_the_event_is_published_at_once() {
      runner.run(
          context -> {
            Listener listener = context.getBean(Listener.class);
            Identity identity = context.getBean(WhoService.class).createIdentity();

            assertThat(listener.events)
                .singleElement()
                .isInstanceOfSatisfying(
                    IdentityCreated.class,
                    e -> assertThat(e.identityId()).isEqualTo(identity.id()));
          });
    }

    @Test
    void inside_a_transaction_the_event_waits_for_commit() {
      runner.run(
          context -> {
            Listener listener = context.getBean(Listener.class);
            TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            tx.executeWithoutResult(
                status -> {
                  context.getBean(WhoService.class).createIdentity();
                  assertThat(listener.events).isEmpty();
                });

            assertThat(listener.events).singleElement().isInstanceOf(IdentityCreated.class);
          });
    }

    @Test
    void a_rolled_back_transaction_publishes_nothing() {
      runner.run(
          context -> {
            Listener listener = context.getBean(Listener.class);
            TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            tx.executeWithoutResult(
                status -> {
                  context.getBean(WhoService.class).createIdentity();
                  status.setRollbackOnly();
                });

            assertThat(listener.events).isEmpty();
          });
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Failing_listeners {

    @Test
    void a_throwing_listener_is_logged_and_does_not_reach_the_caller(CapturedOutput output) {
      runner.run(
          context -> {
            context.getBean(Listener.class).failing = true;

            Identity identity = context.getBean(WhoService.class).createIdentity();

            assertThat(identity).isNotNull();
            assertThat(output.getOut())
                .contains("A listener failed on IdentityCreated: java.lang.IllegalStateException")
                .doesNotContain("secret-detail");
          });
    }

    @Test
    void a_throwing_listener_after_commit_does_not_undo_the_change() {
      runner.run(
          context -> {
            WhoService whoService = context.getBean(WhoService.class);
            UUID id = whoService.createIdentity().id();
            Listener listener = context.getBean(Listener.class);
            listener.events.clear();
            listener.failing = true;

            TransactionTemplate tx =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            assertThatCode(
                    () ->
                        tx.executeWithoutResult(
                            status -> {
                              assertThat(whoService.deactivate(id)).isTrue();
                              assertThat(listener.events).isEmpty();
                            }))
                .doesNotThrowAnyException();

            assertThat(listener.events).singleElement().isInstanceOf(IdentityDeactivated.class);
            assertThat(
                    context
                        .getBean(JdbcClient.class)
                        .sql("SELECT status FROM who_identity WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .isEqualTo("DISABLED");
          });
    }
  }

  @Test
  void an_application_publisher_bean_replaces_who_s() {
    WhoEventPublisher custom = event -> {};
    runner
        .withBean(WhoEventPublisher.class, () -> custom)
        .run(context -> assertThat(context.getBean(WhoEventPublisher.class)).isSameAs(custom));
  }
}
