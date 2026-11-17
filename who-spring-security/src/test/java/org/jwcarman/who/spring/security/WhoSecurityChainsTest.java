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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ott.InMemoryOneTimeTokenService;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.pattern.PathPatternParser;

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

  @Configuration
  @EnableWebSecurity
  static class Checked {
    @Bean
    PasswordlessLoginMethod checked() {
      return new PasswordlessLoginMethod() {
        @Override
        public void configure(HttpSecurity http) {
          // no endpoint: a request the check lets through reaches authorization, a 401
        }

        @Override
        public Set<String> publicPaths() {
          return Set.of("/who/email/send");
        }

        @Override
        public Map<String, String> humanCheckedPaths() {
          return Map.of("/who/email/send", "email-send");
        }

        @Override
        public String name() {
          return "checked";
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

  static final AtomicInteger CHECKS = new AtomicInteger();

  @Configuration
  static class Refusing {
    @Bean
    WhoHumanCheck robots() {
      return new WhoHumanCheck() {
        @Override
        public boolean passes(HumanCheckRequest request) {
          CHECKS.incrementAndGet();
          return false;
        }

        @Override
        public String name() {
          return "robots";
        }
      };
    }
  }

  static final List<WhoEvent> PUBLISHED = new CopyOnWriteArrayList<>();

  @Configuration(proxyBeanMethods = false)
  static class Publishing {
    @Bean
    WhoEventPublisher whoEventPublisher() {
      return PUBLISHED::add;
    }
  }

  @Test
  void each_check_is_published_through_the_applications_publisher() {
    PUBLISHED.clear();
    new WebApplicationContextRunner()
        .withUserConfiguration(
            Checked.class, Refusing.class, Publishing.class, PasswordlessChain.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              mockMvc(context)
                  .perform(post("/who/email/send").with(csrf()))
                  .andExpect(status().isForbidden());
              assertThat(PUBLISHED)
                  .filteredOn(HumanCheckPerformed.class::isInstance)
                  .singleElement()
                  .satisfies(
                      event -> {
                        HumanCheckPerformed checked = (HumanCheckPerformed) event;
                        assertThat(checked.check()).isEqualTo("robots");
                        assertThat(checked.action()).isEqualTo("email-send");
                        assertThat(checked.outcome()).isEqualTo(HumanCheckOutcome.REFUSED);
                      });
            });
  }

  @Test
  void with_a_check_the_checked_endpoints_are_checked() {
    CHECKS.set(0);
    new WebApplicationContextRunner()
        .withUserConfiguration(Checked.class, Refusing.class, PasswordlessChain.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              mockMvc(context)
                  .perform(post("/who/email/send").with(csrf()))
                  .andExpect(status().isForbidden())
                  .andExpect(header().string("Who-Error", "human-check-failed"));
              mockMvc(context).perform(get("/anything")).andExpect(status().isUnauthorized());
            });
    assertThat(CHECKS).hasValue(1);
  }

  @Configuration
  static class CaseInsensitivePaths {
    @Bean
    PathPatternParser mvcPatternParser() {
      PathPatternParser parser = new PathPatternParser();
      parser.setCaseSensitive(false);
      return parser;
    }
  }

  @Test
  void the_check_matches_paths_as_the_chain_does() {
    CHECKS.set(0);
    new WebApplicationContextRunner()
        .withUserConfiguration(
            Checked.class, Refusing.class, CaseInsensitivePaths.class, PasswordlessChain.class)
        .run(
            context ->
                mockMvc(context)
                    .perform(post("/who/email/SEND").with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Who-Error", "human-check-failed")));
    assertThat(CHECKS).hasValue(1);
  }

  @Test
  void csrf_refuses_before_the_check() {
    CHECKS.set(0);
    new WebApplicationContextRunner()
        .withUserConfiguration(Checked.class, Refusing.class, PasswordlessChain.class)
        .run(
            context ->
                mockMvc(context)
                    .perform(post("/who/email/send"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Who-Error")));
    assertThat(CHECKS).hasValue(0);
  }

  @Test
  void without_a_check_the_endpoints_are_not_checked() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Checked.class, PasswordlessChain.class)
        .run(
            context ->
                mockMvc(context)
                    .perform(post("/who/email/send").with(csrf()))
                    .andExpect(status().isUnauthorized()));
  }

  @Test
  void the_startup_line_names_the_check() {
    assertThat(infoLines(Checked.class, Refusing.class, PasswordlessChain.class))
        .containsExactly(
            "who: this chain signs browsers in with checked under /who, human check: robots");
  }

  @Test
  void the_startup_line_does_not_name_a_check_no_path_uses() {
    assertThat(infoLines(Magic.class, Refusing.class, PasswordlessChain.class))
        .containsExactly("who: this chain signs browsers in with magic under /who");
  }

  @Configuration
  static class OneTimeTokens {
    @Bean
    PasswordlessLoginMethod oneTimeTokens() {
      return new PasswordlessLoginMethod() {
        @Override
        public void configure(HttpSecurity http) {
          http.oneTimeTokenLogin(
              ott ->
                  ott.tokenGeneratingUrl("/who/email/send")
                      .loginProcessingUrl("/who/email/sign-in")
                      .tokenService(new InMemoryOneTimeTokenService())
                      .tokenGenerationSuccessHandler((request, response, token) -> {})
                      .authenticationProvider(mock(AuthenticationProvider.class))
                      .showDefaultSubmitPage(false));
        }

        @Override
        public Map<String, String> humanCheckedPaths() {
          return Map.of("/who/email/send", "email-send", "/who/email/sign-in", "email-sign-in");
        }
      };
    }
  }

  @Test
  void the_check_sits_after_csrf_and_before_the_one_time_token_filters() {
    new WebApplicationContextRunner()
        .withUserConfiguration(
            Magic.class, OneTimeTokens.class, Refusing.class, PasswordlessChain.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              List<String> filters =
                  context
                      .getBean(FilterChainProxy.class)
                      .getFilterChains()
                      .getFirst()
                      .getFilters()
                      .stream()
                      .map(filter -> filter.getClass().getSimpleName())
                      .toList();
              assertThat(filters.indexOf("HumanCheckFilter"))
                  .isEqualTo(filters.indexOf("CsrfFilter") + 1)
                  .isLessThan(filters.indexOf("GenerateOneTimeTokenFilter"))
                  .isLessThan(filters.indexOf("OneTimeTokenAuthenticationFilter"));
            });
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
