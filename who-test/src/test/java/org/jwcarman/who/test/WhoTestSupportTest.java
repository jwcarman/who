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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = WhoTestSupportTest.TestApp.class)
class WhoTestSupportTest {

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

  private MockMvc mvc;

  private final Identity identity = Identity.create();
  private final UUID credentialId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, Set.of("task.read"))));
  }

  @Test
  void signedInSessionIsResolvedOnTheNextRequest() throws Exception {
    MockHttpSession session = WhoTestSessions.signedIn(identity.id(), credentialId);

    mvc.perform(get("/who/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityId").value(identity.id().toString()))
        .andExpect(jsonPath("$.permissions[0]").value("task.read"));
  }

  @Test
  void browserWithoutSessionGets401() throws Exception {
    new WhoMockMvcBrowser(mvc).perform(get("/who/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void browserEchoesTheCsrfCookieOnPostAndDelete() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);

    // no token fetched yet: the browser gets one first, then the unsafe request passes CSRF
    browser.perform(post("/who/me")).andExpect(status().isMethodNotAllowed());
    browser.perform(delete("/who/me")).andExpect(status().isMethodNotAllowed());
    assertThat(browser.cookie("XSRF-TOKEN")).isPresent();
  }

  @Test
  void browserSurvivesLogoutAndSignsInAgain() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/who/me")).andExpect(status().isOk());
    String before = browser.cookie("XSRF-TOKEN").orElseThrow().getValue();

    // an unsafe request needs no manual token: the browser fetches and echoes one
    browser.perform(post("/logout")).andExpect(status().isNoContent());

    assertThat(browser.cookie("XSRF-TOKEN")).isEmpty();
    browser.perform(get("/who/me")).andExpect(status().isUnauthorized());

    browser.signedInAs(identity.id(), credentialId);
    browser.perform(get("/who/me")).andExpect(status().isOk());
    assertThat(browser.cookie("XSRF-TOKEN").orElseThrow().getValue()).isNotEqualTo(before);
  }

  @Test
  void unsafeRequestWithoutSessionFetchesATokenFirst() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc);

    int status = browser.perform(post("/who/me")).andReturn().getResponse().getStatus();

    assertThat(status).isNotEqualTo(403);
    assertThat(browser.cookie("XSRF-TOKEN")).isPresent();
  }

  @Test
  void callerPostProcessorRunsOncePerPerform() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);

    browser
        .perform(
            get("/who/me")
                .with(
                    r -> {
                      runs.incrementAndGet();
                      return r;
                    }))
        .andExpect(status().isOk());

    assertThat(runs).hasValue(1);
  }
}
