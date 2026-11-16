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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.RecentSignInAuthorizationManager;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.jwcarman.who.spring.security.RequiresRecentSignIn;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.web.ReauthenticationRequiredException;
import org.jwcarman.who.spring.security.web.RecentSignInUnavailableException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@code @RequiresRecentSignIn} on beans: enforced by who's method security. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoRecentSignInMethodTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  @Target({ElementType.METHOD, ElementType.TYPE})
  @Retention(RetentionPolicy.RUNTIME)
  @RequiresRecentSignIn(within = "2m")
  @interface Sensitive {}

  static class Keys {
    @RequiresRecentSignIn
    public String make() {
      return "made";
    }

    @RequiresRecentSignIn(within = "1m")
    public String quick() {
      return "quick";
    }

    @Sensitive
    public String sensitive() {
      return "sensitive";
    }

    public String plain() {
      return "plain";
    }
  }

  @RequiresRecentSignIn
  static class Admin {
    @RequiresPermission("admin.write")
    public String write() {
      return "written";
    }

    public String read() {
      return "read";
    }
  }

  @Sensitive
  static class MetaOnType {
    public String act() {
      return "acted";
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
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    Keys keys() {
      return new Keys();
    }

    @Bean
    Admin admin() {
      return new Admin();
    }

    @Bean
    MetaOnType metaOnType() {
      return new MetaOnType();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class OwnMethodSecurityConfig {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(WhoTestAutoConfigurations.who())
          .withUserConfiguration(AppConfig.class)
          .withPropertyValues("who.email-link.enabled=false", "who.passkey.enabled=false");

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  /** A browser session that signed in {@code ago} before now. */
  private static void signedInAgo(Duration ago, String... permissions) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            WhoAuthenticationToken.signedIn(
                new WhoPrincipal(Identity.create(), Set.of(permissions)),
                UUID.randomUUID(),
                SignInMethod.EMAIL_CODE,
                NOW.minus(ago)));
  }

  @Test
  void a_recent_sign_in_may() {
    runner.run(
        context -> {
          signedInAgo(Duration.ofMinutes(9));
          assertThat(context.getBean(Keys.class).make()).isEqualTo("made");
        });
  }

  @Test
  void a_stale_sign_in_must_sign_in_again() {
    runner.run(
        context -> {
          signedInAgo(Duration.ofMinutes(11));
          Keys keys = context.getBean(Keys.class);
          assertThatThrownBy(keys::make).isInstanceOf(ReauthenticationRequiredException.class);
          assertThat(keys.plain()).isEqualTo("plain");
        });
  }

  @Test
  void the_property_sets_the_window() {
    runner
        .withPropertyValues("who.security.recent-sign-in=20m")
        .run(
            context -> {
              signedInAgo(Duration.ofMinutes(15));
              assertThat(context.getBean(Keys.class).make()).isEqualTo("made");
              assertThat(context.getBean(RecentSignInAuthorizationManager.class).window())
                  .isEqualTo(Duration.ofMinutes(20));
            });
  }

  @Test
  void within_overrides_the_window() {
    runner.run(
        context -> {
          signedInAgo(Duration.ofMinutes(2));
          Keys keys = context.getBean(Keys.class);
          assertThatThrownBy(keys::quick).isInstanceOf(ReauthenticationRequiredException.class);
          assertThat(keys.make()).isEqualTo("made");
        });
  }

  @Test
  void a_meta_annotation_works_on_methods_and_types() {
    runner.run(
        context -> {
          signedInAgo(Duration.ofMinutes(3));
          assertThatThrownBy(context.getBean(Keys.class)::sensitive)
              .isInstanceOf(ReauthenticationRequiredException.class);
          assertThatThrownBy(context.getBean(MetaOnType.class)::act)
              .isInstanceOf(ReauthenticationRequiredException.class);
          signedInAgo(Duration.ofMinutes(1));
          assertThat(context.getBean(MetaOnType.class).act()).isEqualTo("acted");
        });
  }

  @Test
  void a_type_level_annotation_covers_every_method_and_the_permission_comes_first() {
    runner.run(
        context -> {
          Admin admin = context.getBean(Admin.class);
          signedInAgo(Duration.ofMinutes(11));
          assertThatThrownBy(admin::read).isInstanceOf(ReauthenticationRequiredException.class);
          assertThatThrownBy(admin::write)
              .isInstanceOf(AccessDeniedException.class)
              .isNotInstanceOf(RecentSignInUnavailableException.class);
          signedInAgo(Duration.ofMinutes(11), "admin.write");
          assertThatThrownBy(admin::write).isInstanceOf(ReauthenticationRequiredException.class);
          signedInAgo(Duration.ofMinutes(1), "admin.write");
          assertThat(admin.write()).isEqualTo("written");
        });
  }

  @Test
  void an_api_key_is_refused() {
    runner.run(
        context -> {
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new WhoAuthenticationToken(
                      new WhoPrincipal(Identity.create(), Set.of()),
                      UUID.randomUUID(),
                      SignInMethod.API_KEY));
          assertThatThrownBy(context.getBean(Keys.class)::make)
              .isInstanceOf(RecentSignInUnavailableException.class);
        });
  }

  @Test
  void an_app_with_its_own_method_security_still_enforces() {
    runner
        .withUserConfiguration(OwnMethodSecurityConfig.class)
        .run(
            context -> {
              signedInAgo(Duration.ofMinutes(11));
              assertThatThrownBy(context.getBean(Keys.class)::make)
                  .isInstanceOf(ReauthenticationRequiredException.class);
            });
  }

  @Test
  void property_false_still_enforces_it_in_a_spring_boot_app() {
    runner
        .withPropertyValues("who.method-security.enabled=false")
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class))
        .run(
            context -> {
              signedInAgo(Duration.ofMinutes(11));
              assertThatThrownBy(context.getBean(Keys.class)::make)
                  .isInstanceOf(ReauthenticationRequiredException.class);
            });
  }

  @Test
  void an_app_that_turns_method_security_on_itself_still_enforces() {
    runner
        .withPropertyValues("who.method-security.enabled=false")
        .withUserConfiguration(OwnMethodSecurityConfig.class)
        .run(
            context -> {
              signedInAgo(Duration.ofMinutes(11));
              assertThatThrownBy(context.getBean(Keys.class)::make)
                  .isInstanceOf(ReauthenticationRequiredException.class);
            });
  }
}
