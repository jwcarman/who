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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootTest(classes = PasswordlessConfigurerTest.TestApp.class)
class PasswordlessConfigurerTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final String MARKER_HEADER = "X-Test-Login-Method";

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
    PasswordlessLoginMethod markerLoginMethod() {
      return http -> http.addFilterBefore(new MarkerFilter(), CsrfFilter.class);
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  static class MarkerFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      response.setHeader(MARKER_HEADER, "applied");
      chain.doFilter(request, response);
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
  }

  private MockHttpSession signedIn() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identity.id(), credentialId, NOW));
    return session;
  }

  private void resolvesTo(Set<String> permissions) {
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, permissions)));
  }

  @Test
  void unauthenticatedIs401NotARedirect() throws Exception {
    mvc.perform(get("/who/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void meReturnsTheFreshPrincipal() throws Exception {
    resolvesTo(Set.of("task.read"));

    mvc.perform(get("/who/me").session(signedIn()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityId").value(identity.id().toString()))
        .andExpect(jsonPath("$.permissions.length()").value(1))
        .andExpect(jsonPath("$.permissions[0]").value("task.read"));
  }

  @Test
  void suspendedIdentityLosesAccessOnTheNextRequest() throws Exception {
    MockHttpSession session = signedIn();
    resolvesTo(Set.of("task.read"));
    mvc.perform(get("/who/me").session(session)).andExpect(status().isOk());

    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    mvc.perform(get("/who/me").session(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRequiresCsrfAndAnswers204() throws Exception {
    resolvesTo(Set.of("task.read"));
    MockHttpSession session = signedIn();

    mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
    assertThat(session.isInvalid()).isFalse();

    // as an SPA does: read the XSRF-TOKEN cookie and echo it in the X-XSRF-TOKEN header
    Cookie xsrf =
        mvc.perform(get("/who/me").session(session))
            .andReturn()
            .getResponse()
            .getCookie("XSRF-TOKEN");
    assertThat(xsrf).isNotNull();

    mvc.perform(
            post("/logout").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge("XSRF-TOKEN", 0));
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void csrfCookieIsReadableByScripts() throws Exception {
    mvc.perform(get("/who/me"))
        .andExpect(cookie().exists("XSRF-TOKEN"))
        .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
  }

  @Test
  void referrerPolicyIsNoReferrer() throws Exception {
    mvc.perform(get("/who/me")).andExpect(header().string("Referrer-Policy", "no-referrer"));
  }

  @Test
  void loginMethodBeansAreApplied() throws Exception {
    mvc.perform(get("/who/me")).andExpect(header().string(MARKER_HEADER, "applied"));
  }

  // ---------------------------------------------------------------- startup failures

  @Configuration
  @EnableWebSecurity
  static class BaseBadApp {
    @Bean
    WhoService whoService() {
      return mock(WhoService.class);
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(WhoService whoService) {
      return new WhoSessionSecurityContextRepository(whoService, Clock.systemUTC());
    }
  }

  @Configuration
  static class CsrfDisabledBeforeApp {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
      http.csrf(c -> c.disable()).with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  @Configuration
  static class CsrfDisabledAfterApp {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
      http.with(WhoWebSecurity.passwordless(), withDefaults()).csrf(c -> c.disable());
      return http.build();
    }
  }

  @Configuration
  static class StatelessApp {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
      http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  private static final String CSRF_MESSAGE =
      "WhoWebSecurity.passwordless() requires CSRF protection; remove csrf.disable() from this chain";

  private static void assertStartupFailsWith(Class<?> chainConfig, String message) {
    new WebApplicationContextRunner()
        .withUserConfiguration(BaseBadApp.class, chainConfig)
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(message));
  }

  @Test
  void csrfDisabledChainFailsAtStartup() {
    assertStartupFailsWith(CsrfDisabledBeforeApp.class, CSRF_MESSAGE);
  }

  @Test
  void csrfDisabledAfterWithFailsAtStartup() {
    assertStartupFailsWith(CsrfDisabledAfterApp.class, CSRF_MESSAGE);
  }

  @Test
  void statelessChainFailsAtStartup() {
    assertStartupFailsWith(
        StatelessApp.class,
        "WhoWebSecurity.passwordless() needs sessions; this chain is STATELESS");
  }
}
