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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class WhoMethodSecurityTest {

  static class Guarded {
    @RequiresPermission("task.read")
    public String read() {
      return "ok";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AppConfig {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    @Bean
    Guarded guarded() {
      return new Guarded();
    }

    @Bean
    EmailLinkSender emailLinkSender() {
      return (address, link) -> {};
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class OwnMethodSecurityConfig {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
          .withUserConfiguration(AppConfig.class)
          .withPropertyValues("who.email-link.link-base-url=https://app.example.com/signin");

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticateWith(String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("user", "pw", authorities));
  }

  @Test
  void requiresPermissionIsEnforcedWithoutEnableMethodSecurity() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          Guarded guarded = context.getBean(Guarded.class);
          authenticateWith("other.perm");
          assertThatThrownBy(guarded::read).isInstanceOf(AccessDeniedException.class);
          authenticateWith("task.read");
          assertThat(guarded.read()).isEqualTo("ok");
        });
  }

  @Test
  void propertyFalseLeavesMethodSecurityOff() {
    runner
        .withPropertyValues("who.method-security.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              authenticateWith("other.perm");
              assertThatCode(() -> context.getBean(Guarded.class).read())
                  .doesNotThrowAnyException();
            });
  }

  @Test
  void appWithItsOwnMethodSecurityStillEnforces() {
    runner
        .withUserConfiguration(OwnMethodSecurityConfig.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Guarded guarded = context.getBean(Guarded.class);
              authenticateWith("other.perm");
              assertThatThrownBy(guarded::read).isInstanceOf(AccessDeniedException.class);
            });
  }
}
