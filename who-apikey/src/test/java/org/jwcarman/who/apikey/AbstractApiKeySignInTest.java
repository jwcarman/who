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
package org.jwcarman.who.apikey;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Base class for tests that sign in with API keys over HTTP, through a chain that adds them with
 * {@link WhoApiKeySecurity#apiKey()}: {@code /open/**} permits anonymous access, everything else
 * needs an authentication. {@code /open/whoami} and {@code /secure/whoami} answer with the name of
 * the request's authentication (an identity id for an API key), or {@code anonymous}.
 */
@Import(AbstractApiKeySignInTest.Chain.class)
abstract class AbstractApiKeySignInTest extends AbstractApiKeyTest {

  static final String HEADER = "X-API-Key";
  static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
  static final String ANONYMOUS = "anonymous";

  /** The events the API key filter published, in order. */
  static final List<WhoEvent> EVENTS = new CopyOnWriteArrayList<>();

  /** What who handed to the application, in order. */
  static final List<Exception> HANDED_OFF = new CopyOnWriteArrayList<>();

  @Autowired private WebApplicationContext context;
  @Autowired private FilterChainProxy springSecurityFilterChain;

  /** Sends requests through the chain. */
  MockMvc mvc;

  @BeforeEach
  void setUpChain() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    EVENTS.clear();
    HANDED_OFF.clear();
  }

  @TestConfiguration
  @EnableWebSecurity
  static class Chain {

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
        ApiKeyCredentialRepository repository, WhoService whoService) {
      return new ApiKeyAuthenticationFilter(
          new ApiKeyAuthenticationProvider(
              repository, whoService, EVENTS::add, Clock.fixed(NOW, ZoneOffset.UTC)),
          HEADER);
    }

    @Bean
    WhoExceptionHandoff whoExceptionHandoff() {
      // records, then lets who fall back to the bare status
      return new WhoExceptionHandoff((request, response, exception) -> !HANDED_OFF.add(exception));
    }

    @Bean
    SecurityFilterChain apiKeyChain(HttpSecurity http) {
      http.authorizeHttpRequests(
              a -> a.requestMatchers("/open/**").permitAll().anyRequest().authenticated())
          .csrf(c -> c.disable());
      http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
      return http.build();
    }

    /** A functional endpoint, so that component scanning has no controller to find twice. */
    @Bean
    RouterFunction<ServerResponse> whoami() {
      return RouterFunctions.route()
          .GET("/open/whoami", request -> ServerResponse.ok().body(currentName()))
          .GET("/secure/whoami", request -> ServerResponse.ok().body(currentName()))
          .build();
    }

    private static String currentName() {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      return authentication == null || authentication instanceof AnonymousAuthenticationToken
          ? ANONYMOUS
          : authentication.getName();
    }
  }
}
