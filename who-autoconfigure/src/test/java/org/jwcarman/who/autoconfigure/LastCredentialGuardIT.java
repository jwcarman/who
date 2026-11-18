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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Two concurrent removals of an identity's only two credentials: exactly one may succeed. */
@SpringBootTest(
    classes = LastCredentialGuardIT.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.enabled=false",
      "who.jwt.enabled=false",
      "who.oidc.enabled=false",
      "who.turnstile.enabled=false",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LastCredentialGuardIT {

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    WhoEmailSender whoEmailSender() {
      return email -> {
        // sign-in emails are not under test
      };
    }
  }

  @Autowired private WhoService whoService;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private LastCredentialGuard guard;

  @Test
  void concurrent_removals_leave_one_credential() throws Exception {
    Identity identity = whoService.createIdentity();
    EmailCredential email = emailCredentialService.register(identity, "carol@example.com");
    EmailCredential other = emailCredentialService.register(identity, "carol@example.org");
    assertThat(guard.counts(identity.id())).containsEntry("email", 2L);

    CountDownLatch go = new CountDownLatch(1);
    // Each removal waits here, after the count, for the other to arrive. With the row lock the
    // other is blocked in lockById, so the wait times out; without it both pass the count.
    CountDownLatch bothCounted = new CountDownLatch(2);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> removeEmail =
          pool.submit(
              removal(
                  go,
                  identity.id(),
                  () -> {
                    meet(bothCounted);
                    emailCredentialService.revoke(email.id());
                    return true;
                  }));
      Future<Boolean> removeOther =
          pool.submit(
              removal(
                  go,
                  identity.id(),
                  () -> {
                    meet(bothCounted);
                    emailCredentialService.revoke(other.id());
                    return true;
                  }));
      go.countDown();

      List<Boolean> outcomes = List.of(removeEmail.get(), removeOther.get());

      assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }

    long remaining = guard.counts(identity.id()).values().stream().mapToLong(Long::longValue).sum();
    assertThat(remaining).isEqualTo(1);
  }

  @Test
  void without_oidc_a_linked_account_does_not_count() {
    Identity identity = whoService.createIdentity();
    emailCredentialService.register(identity, "dave@example.com");
    federatedCredentialService.link(identity, "https://issuer.example.com", "dave-subject");

    assertThat(guard.counts(identity.id())).containsOnlyKeys("email");
  }

  private Callable<Boolean> removal(CountDownLatch go, UUID identityId, Supplier<Boolean> removal) {
    return () -> {
      go.await();
      try {
        return guard.removeIfNotLast(identityId, removal);
      } catch (LastCredentialException _) {
        return false;
      }
    };
  }

  private static void meet(CountDownLatch bothCounted) {
    bothCounted.countDown();
    try {
      bothCounted.await(500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
