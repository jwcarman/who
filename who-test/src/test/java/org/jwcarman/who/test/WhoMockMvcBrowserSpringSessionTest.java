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
package org.jwcarman.who.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = WhoMockMvcBrowserSpringSessionTest.TestApp.class)
class WhoMockMvcBrowserSpringSessionTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  @Configuration
  @EnableAutoConfiguration
  @Import(WhoMeController.class)
  static class TestApp {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    MapSessionRepository sessionRepository() {
      return new MapSessionRepository(new ConcurrentHashMap<>());
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
        WhoService whoService, Clock clock) {
      return new WhoSessionSecurityContextRepository(whoService, clock);
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  @MockitoBean private WhoService whoService;

  @Autowired private WebApplicationContext context;

  @Autowired private MapSessionRepository repository;

  private MockMvc mvc;

  private final Identity identity = Identity.create();
  private final UUID credentialId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    SessionRepositoryFilter<MapSession> filter = new SessionRepositoryFilter<>(repository);
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(filter)
            .apply(springSecurity())
            .build();
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("task.read"))));
  }

  @Test
  void browserSignsInThroughTheSessionRepositoryAndItsCookie() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);

    browser
        .perform(get("/who/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityId").value(identity.id().toString()));
    assertThat(browser.cookie("SESSION")).isPresent();
  }
}
