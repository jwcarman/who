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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.admin.LastAdminException;
import org.jwcarman.who.admin.LastAdminGuard;
import org.jwcarman.who.admin.WhoAdminPermissions;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.WhoEmailSender;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link LastAdminGuard} against PostgreSQL: no deactivation or role removal leaves no active
 * identity holding {@link WhoAdminPermissions#IDENTITIES_MANAGE}, two at once included.
 */
@SpringBootTest(
    classes = LastAdminGuardIT.App.class,
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
class LastAdminGuardIT {

  static final String ADMIN = "last-admin-test-admin";
  static final String OWNER = "last-admin-test-owner";

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
  @Autowired private RbacService rbacService;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private LastAdminGuard guard;
  @Autowired private JdbcClient jdbcClient;

  private Role admin;
  private Role owner;
  private Identity alice;
  private Identity bob;

  private Role role(String name) {
    Role role = rbacService.findRole(name).orElseGet(() -> rbacService.createRole(name));
    if (!rbacService.findPermissions(role).contains(WhoAdminPermissions.IDENTITIES_MANAGE)) {
      rbacService.addPermissionToRole(role, WhoAdminPermissions.IDENTITIES_MANAGE);
    }
    return role;
  }

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    admin = role(ADMIN);
    owner = role(OWNER);
    alice = whoService.createIdentity();
    bob = whoService.createIdentity();
  }

  private long holders() {
    return jdbcClient
        .sql(
            """
            SELECT COUNT(DISTINCT ir.identity_id) FROM who_identity_role ir
            JOIN who_role_permission p ON p.role_id = ir.role_id
            JOIN who_identity i ON i.id = ir.identity_id
            WHERE p.permission = :permission AND i.status = 'ACTIVE'
            """)
        .param("permission", WhoAdminPermissions.IDENTITIES_MANAGE)
        .query(Long.class)
        .single();
  }

  private Boolean removeAdmin(Identity identity) {
    rbacService.removeRoleFromIdentity(identity, admin);
    return true;
  }

  @Test
  void enforces_with_who_rbac_permissions() {
    assertThat(guard.isEnforcing()).isTrue();
  }

  @Nested
  class The_only_holder {

    @BeforeEach
    void aliceAlone() {
      rbacService.assignRoleToIdentity(alice, admin);
    }

    @Test
    void cannot_lose_the_role_that_grants_it() {
      assertThatThrownBy(() -> guard.removeRole(alice.id(), admin.id(), () -> removeAdmin(alice)))
          .isInstanceOf(LastAdminException.class);
      assertThat(rbacService.findRolesForIdentity(alice.id())).containsExactly(admin);
    }

    @Test
    void cannot_be_deactivated() {
      assertThatThrownBy(
              () -> guard.deactivate(alice.id(), () -> whoService.deactivate(alice.id())))
          .isInstanceOf(LastAdminException.class);
      assertThat(identityRepository.findById(alice.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void may_lose_one_role_while_another_grants_it() {
      rbacService.assignRoleToIdentity(alice, owner);

      assertThat(guard.removeRole(alice.id(), admin.id(), () -> removeAdmin(alice))).isTrue();
      assertThat(holders()).isEqualTo(1);
    }

    @Test
    void does_not_stop_changes_to_someone_who_does_not_hold_it() {
      assertThat(guard.deactivate(bob.id(), () -> whoService.deactivate(bob.id()))).isTrue();
    }
  }

  @Nested
  class Two_holders {

    @BeforeEach
    void aliceAndBob() {
      rbacService.assignRoleToIdentity(alice, admin);
      rbacService.assignRoleToIdentity(bob, admin);
    }

    @Test
    void one_may_be_deactivated_but_not_then_the_other() {
      assertThat(guard.deactivate(bob.id(), () -> whoService.deactivate(bob.id()))).isTrue();

      assertThatThrownBy(() -> guard.removeRole(alice.id(), admin.id(), () -> removeAdmin(alice)))
          .isInstanceOf(LastAdminException.class);
    }

    @Test
    void cannot_both_be_demoted_at_once() throws Exception {
      CountDownLatch go = new CountDownLatch(1);
      // Each demotion waits here, after its count, for the other to arrive. With the lock the
      // other is blocked taking it, so the wait times out; without it both pass the count.
      CountDownLatch bothCounted = new CountDownLatch(2);
      try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        Future<Boolean> demoteAlice = pool.submit(demotion(go, bothCounted, alice));
        Future<Boolean> demoteBob = pool.submit(demotion(go, bothCounted, bob));
        go.countDown();

        List<Boolean> outcomes = List.of(demoteAlice.get(), demoteBob.get());

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
      }
      assertThat(holders()).isEqualTo(1);
    }

    private Callable<Boolean> demotion(
        CountDownLatch go, CountDownLatch bothCounted, Identity identity) {
      return () -> {
        go.await();
        try {
          return guard.removeRole(
              identity.id(),
              admin.id(),
              () -> {
                meet(bothCounted);
                return removeAdmin(identity);
              });
        } catch (LastAdminException e) {
          return false;
        }
      };
    }
  }

  @Test
  void with_no_holders_nothing_is_refused() {
    assertThat(holders()).isZero();

    assertThat(guard.deactivate(alice.id(), () -> whoService.deactivate(alice.id()))).isTrue();
  }

  private static void meet(CountDownLatch bothCounted) {
    bothCounted.countDown();
    try {
      bothCounted.await(500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
