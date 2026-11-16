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
package org.jwcarman.who.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link WhoSecurity}'s chains built from the Spring Security configuration alone: no who
 * autoconfiguration, so no {@code WhoExceptionHandoff} bean.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSecurityChainsTest {

  @Configuration
  @EnableWebSecurity
  static class Bearer {
    @Bean
    StatelessLoginMethod bearer() {
      return new StatelessLoginMethod() {
        @Override
        public void configure(HttpSecurity http) {
          // signs no one in: these tests are about the answers
        }

        @Override
        public RequestMatcher carriesCredential() {
          return AnyRequestMatcher.INSTANCE;
        }

        @Override
        public Optional<String> challenge() {
          return Optional.of("Bearer");
        }

        @Override
        public String name() {
          return "bearer";
        }
      };
    }
  }

  @Configuration
  static class StatelessChain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Configuration
  static class AllWithoutCsrf {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.csrf(c -> c.disable())
          .with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Configuration
  @EnableWebSecurity
  static class AllWithNothing {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Test
  void stateless_without_a_handoff_bean_answers_a_bare_401_with_the_challenge() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Bearer.class, StatelessChain.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              mockMvc(context)
                  .perform(get("/anything"))
                  .andExpect(status().isUnauthorized())
                  .andExpect(header().stringValues(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
            });
  }

  @Test
  void all_on_a_chain_with_csrf_off_still_mounts_the_stateless_methods() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Bearer.class, AllWithoutCsrf.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              mockMvc(context)
                  .perform(get("/anything"))
                  .andExpect(status().isUnauthorized())
                  .andExpect(header().stringValues(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
            });
  }

  @Test
  void all_with_no_sign_in_method_warns_and_answers_401() {
    Logger logger = (Logger) LoggerFactory.getLogger(WhoSecurityConfigurer.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      new WebApplicationContextRunner()
          .withUserConfiguration(AllWithNothing.class)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                mockMvc(context).perform(get("/anything")).andExpect(status().isUnauthorized());
              });
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list)
        .filteredOn(e -> e.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(WhoSecurityConfigurer.NO_METHODS);
  }

  @Configuration
  @EnableWebSecurity
  static class Magic {
    @Bean
    PasswordlessLoginMethod magic() {
      return new PasswordlessLoginMethod() {
        @Override
        public void configure(HttpSecurity http) {
          // signs no one in: these tests are about the startup line
        }

        @Override
        public String name() {
          return "magic";
        }
      };
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository() {
      Clock clock = Clock.systemUTC();
      return new WhoSessionSecurityContextRepository(
          mock(WhoService.class),
          InMemorySessionRecords.sessions(clock, WhoEventPublisher.NOOP),
          clock,
          WhoEventPublisher.NOOP);
    }
  }

  @Configuration
  static class Auth {
    @Bean
    WhoPaths whoPaths() {
      return new WhoPaths("/auth");
    }
  }

  @Configuration
  static class PasswordlessChain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoWebSecurity.passwordless(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Configuration
  static class AllChain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  static final AtomicBoolean ENDPOINTS_APPLIED = new AtomicBoolean();

  @Configuration
  static class Endpoints {
    @Bean
    BrowserEndpoints someEndpoints() {
      return http -> ENDPOINTS_APPLIED.set(true);
    }
  }

  @Test
  void browser_endpoints_are_applied_but_not_named_as_a_sign_in_method() {
    ENDPOINTS_APPLIED.set(false);

    assertThat(infoLines(Magic.class, Endpoints.class, PasswordlessChain.class))
        .containsExactly("who: this chain signs browsers in with magic under /who");
    assertThat(ENDPOINTS_APPLIED).isTrue();
  }

  @Test
  void stateless_logs_the_methods_it_accepts() {
    assertThat(infoLines(Bearer.class, StatelessChain.class))
        .containsExactly("who: this chain accepts bearer");
  }

  @Test
  void all_with_browser_and_stateless_methods_logs_each_kind_once() {
    assertThat(infoLines(Magic.class, Bearer.class, AllChain.class))
        .containsExactlyInAnyOrder(
            "who: this chain signs browsers in with magic under /who",
            "who: this chain accepts bearer");
  }

  @Test
  void all_with_only_stateless_methods_logs_no_browser_line() {
    assertThat(infoLines(Bearer.class, AllWithoutCsrf.class))
        .containsExactly("who: this chain accepts bearer");
  }

  @Test
  void the_browser_line_names_a_custom_root() {
    assertThat(infoLines(Magic.class, Auth.class, PasswordlessChain.class))
        .containsExactly("who: this chain signs browsers in with magic under /auth");
  }

  @Test
  void all_with_no_methods_logs_no_methods_line() {
    assertThat(infoLines(AllWithNothing.class)).isEmpty();
  }

  /** Starts a context from the configurations and returns the who configurers' INFO lines. */
  private static List<String> infoLines(Class<?>... configurations) {
    List<Logger> loggers =
        List.of(
            (Logger) LoggerFactory.getLogger(PasswordlessConfigurer.class),
            (Logger) LoggerFactory.getLogger(WhoStatelessConfigurer.class),
            (Logger) LoggerFactory.getLogger(WhoSecurityConfigurer.class));
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    loggers.forEach(logger -> logger.addAppender(logs));
    try {
      new WebApplicationContextRunner()
          .withUserConfiguration(configurations)
          .run(context -> assertThat(context).hasNotFailed());
    } finally {
      loggers.forEach(logger -> logger.detachAppender(logs));
    }
    List<String> lines = new ArrayList<>();
    for (ILoggingEvent event : logs.list) {
      if (event.getLevel() == Level.INFO) {
        lines.add(event.getFormattedMessage());
      }
    }
    return lines;
  }

  private static MockMvc mockMvc(WebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }
}
