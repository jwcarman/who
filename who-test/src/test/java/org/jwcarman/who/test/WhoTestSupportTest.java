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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.spring.security.WhoSession;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

@SpringBootTest(classes = WhoTestSupportTest.TestApp.class)
class WhoTestSupportTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  @Configuration
  @EnableAutoConfiguration
  @Import({WhoMeController.class, WhoTestSupportTest.CrumbController.class})
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

  @RestController
  static class CrumbController {

    @GetMapping("/crumb/set")
    void set(HttpServletResponse response) {
      response.addCookie(new Cookie("crumb", "chocolate"));
    }

    @GetMapping("/crumb/clear")
    void clear(HttpServletResponse response) {
      response.addCookie(new Cookie("crumb", null));
    }

    @GetMapping("/crumb/empty")
    void empty(HttpServletResponse response) {
      response.addCookie(new Cookie("crumb", ""));
    }

    @GetMapping("/crumb/expire")
    void expire(HttpServletResponse response) {
      Cookie cookie = new Cookie("crumb", "stale");
      cookie.setMaxAge(0);
      response.addCookie(cookie);
    }

    @GetMapping("/crumb/echo")
    String echo(HttpServletRequest request) {
      return Arrays.stream(request.getCookies())
          .filter(c -> !"XSRF-TOKEN".equals(c.getName()))
          .map(c -> c.getName() + "=" + c.getValue())
          .sorted()
          .collect(Collectors.joining(","));
    }

    @GetMapping("/session/rotate")
    void rotate(HttpServletRequest request) {
      request.getSession().invalidate();
      request.getSession(true);
    }

    @GetMapping("/session/drop")
    void drop(HttpServletRequest request) {
      request.getSession().invalidate();
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

  @Test
  void browserDropsACookieTheAppAnswersWithANullValue() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/crumb/set")).andExpect(status().isOk());
    assertThat(browser.cookie("crumb")).isPresent();

    browser.perform(get("/crumb/clear")).andExpect(status().isOk());

    assertThat(browser.cookie("crumb")).isEmpty();
  }

  @Test
  void signedInAtFollowsTheGivenInstant() throws Exception {
    Instant earlier = NOW.minusSeconds(60);
    MockHttpSession session = WhoTestSessions.signedIn(identity.id(), credentialId, earlier);

    assertThat(session.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .isEqualTo(new WhoSession(identity.id(), credentialId, earlier));
    mvc.perform(get("/who/me").session(session)).andExpect(status().isOk());
  }

  @Test
  void browserSignedInAtFollowsTheGivenInstant() throws Exception {
    WhoMockMvcBrowser browser =
        new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId, NOW);

    assertThat(
            browser.session().getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .isEqualTo(new WhoSession(identity.id(), credentialId, NOW));
    browser.perform(get("/who/me")).andExpect(status().isOk());
  }

  @Test
  void signedInSessionCarriesThePrincipalIndexAsProductionDoes() {
    MockHttpSession session = WhoTestSessions.signedIn(identity.id(), credentialId);

    assertThat(session.getAttribute(WhoSessionSecurityContextRepository.PRINCIPAL_INDEX_ATTRIBUTE))
        .isEqualTo(identity.id().toString());
  }

  @Test
  void browserCookieWinsAClashAndOtherCallerCookiesAreKept() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/crumb/set")).andExpect(status().isOk());

    browser
        .perform(get("/crumb/echo").cookie(new Cookie("crumb", "raw"), new Cookie("extra", "1")))
        .andExpect(status().isOk())
        .andExpect(content().string("crumb=chocolate,extra=1"));
  }

  @Test
  void browserDropsAnEmptyValuedCookie() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/crumb/set")).andExpect(status().isOk());

    browser.perform(get("/crumb/empty")).andExpect(status().isOk());

    assertThat(browser.cookie("crumb")).isEmpty();
  }

  @Test
  void browserDropsAnExpiredCookieWithAValue() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/crumb/set")).andExpect(status().isOk());

    browser.perform(get("/crumb/expire")).andExpect(status().isOk());

    assertThat(browser.cookie("crumb")).isEmpty();
  }

  @Test
  void browserAdoptsASessionTheApplicationCreatesInPlaceOfTheOldOne() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    MockHttpSession before = browser.session();

    browser.perform(get("/session/rotate")).andExpect(status().isOk());

    assertThat(browser.session()).isNotSameAs(before);
    assertThat(browser.session().isInvalid()).isFalse();
  }

  @Test
  void browserStartsAFreshSessionAfterTheApplicationInvalidatesItWithoutReplacement()
      throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    MockHttpSession before = browser.session();

    browser.perform(get("/session/drop")).andExpect(status().isOk());

    assertThat(browser.session()).isNotSameAs(before);
    assertThat(browser.session().isInvalid()).isFalse();
  }

  @Test
  void unsafeMethodsOtherThanPostAndDeleteGetTheCsrfHeaderToo() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);

    int status = browser.perform(put("/who/me")).andReturn().getResponse().getStatus();

    assertThat(status).isNotEqualTo(403);
  }

  @Test
  void aRequestWithoutAMethodIsSentWithoutTheCsrfHeader() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/who/me")).andExpect(status().isOk());
    var request =
        get("/who/me")
            .with(
                r -> {
                  r.setMethod(null);
                  return r;
                });

    int status = browser.perform(request).andReturn().getResponse().getStatus();

    assertThat(status).isNotEqualTo(403);
    assertThat(browser.cookie("XSRF-TOKEN")).isPresent();
  }

  @Test
  void aMockMvcWithoutAWebContextFallsBackToAMockHttpSession() {
    MockMvc detached = mock(MockMvc.class);
    when(detached.getDispatcherServlet()).thenReturn(new DispatcherServlet());

    WhoMockMvcBrowser browser =
        new WhoMockMvcBrowser(detached).signedInAs(identity.id(), credentialId);

    assertThat(
            browser.session().getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .isNotNull();
    assertThat(browser.cookie("SESSION")).isEmpty();
  }

  @Test
  void performWithoutCsrfIsRejectedOnAnUnsafeMethod() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.perform(get("/who/me")).andExpect(status().isOk());

    browser.performWithoutCsrf(post("/who/me")).andExpect(status().isForbidden());
  }

  @Test
  void performPassesCsrfWhereTheForgedRequestIsRejected() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);

    browser.perform(post("/who/me")).andExpect(status().isMethodNotAllowed());
  }

  @Test
  void performWithoutCsrfDoesNotPrefetchAToken() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    MockMvc counting =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                (request, response, chain) -> {
                  requests.incrementAndGet();
                  chain.doFilter(request, response);
                })
            .apply(springSecurity())
            .build();
    WhoMockMvcBrowser browser =
        new WhoMockMvcBrowser(counting).signedInAs(identity.id(), credentialId);

    browser.performWithoutCsrf(post("/who/me")).andExpect(status().isForbidden());

    assertThat(requests).hasValue(1);
  }

  @Test
  void performWithoutCsrfStillAppliesAndUpdatesTheCookieJar() throws Exception {
    WhoMockMvcBrowser browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId);
    browser.performWithoutCsrf(get("/crumb/set")).andExpect(status().isOk());

    browser.performWithoutCsrf(get("/crumb/echo")).andExpect(content().string("crumb=chocolate"));
  }
}
