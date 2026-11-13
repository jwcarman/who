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
import static org.mockito.Mockito.when;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SessionEndReason;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The events a browser session publishes when it ends: {@link SignedOut} when the user signs out,
 * and {@link SessionEnded} when a later request finds the stored session no longer resolves.
 */
@SpringBootTest(classes = SessionEventsTest.TestApp.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SessionEventsTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

  /** Every event the chain published, in order. */
  static final List<WhoEvent> EVENTS = new CopyOnWriteArrayList<>();

  @Configuration
  @EnableAutoConfiguration
  @Import(WhoMeController.class)
  static class TestApp {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    WhoEventPublisher whoEventPublisher() {
      return EVENTS::add;
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
        WhoService whoService, Clock clock, WhoEventPublisher publisher) {
      return new WhoSessionSecurityContextRepository(whoService, clock, publisher);
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
  @Autowired private WhoSessionSecurityContextRepository repository;

  private MockMvc mvc;

  private final Identity identity = Identity.create();
  private final UUID credentialId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    EVENTS.clear();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private MockHttpSession signedIn() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identity.id(), credentialId, NOW.minusSeconds(60)));
    return session;
  }

  private void resolvesTo(Resolution resolution) {
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(resolution);
  }

  private Resolution.Resolved active() {
    return new Resolution.Resolved(new WhoPrincipal(identity, Set.of("task.read")));
  }

  private int logout(MockHttpSession session) throws Exception {
    // as an SPA does: read the XSRF-TOKEN cookie and echo it in the X-XSRF-TOKEN header
    Cookie xsrf =
        mvc.perform(get("/who/me").session(session))
            .andReturn()
            .getResponse()
            .getCookie("XSRF-TOKEN");
    assertThat(xsrf).isNotNull();
    return mvc.perform(
            post("/logout").session(session).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Nested
  class Signing_out {

    @Test
    void signing_out_publishes_signed_out() throws Exception {
      resolvesTo(active());

      assertThat(logout(signedIn())).isEqualTo(204);

      assertThat(EVENTS).containsExactly(new SignedOut(NOW, identity.id(), credentialId));
    }

    @Test
    void an_anonymous_logout_publishes_nothing() throws Exception {
      MockHttpSession session = new MockHttpSession();

      assertThat(logout(session)).isEqualTo(204);

      assertThat(EVENTS).isEmpty();
    }

    @Test
    void logging_out_a_request_that_is_not_a_who_session_publishes_nothing() throws Exception {
      Cookie xsrf =
          mvc.perform(get("/who/me").with(user("someone")))
              .andReturn()
              .getResponse()
              .getCookie("XSRF-TOKEN");
      assertThat(xsrf).isNotNull();

      mvc.perform(
              post("/logout")
                  .with(user("someone"))
                  .cookie(xsrf)
                  .header("X-XSRF-TOKEN", xsrf.getValue()))
          .andExpect(status().isNoContent());

      assertThat(EVENTS).isEmpty();
    }
  }

  @Nested
  class Signing_out_without_a_who_session {

    @Test
    void logging_out_an_api_key_authentication_publishes_nothing() {
      WhoAuthenticationToken apiKey =
          new WhoAuthenticationToken(
              new WhoPrincipal(identity, Set.of("task.read")), credentialId, SignInMethod.API_KEY);

      // an API-key request carries no who session, so there is nothing of ours to sign out of
      repository.onLogout(new MockHttpServletRequest(), apiKey);

      assertThat(EVENTS).isEmpty();
    }
  }

  @Nested
  class Ending_a_session {

    @Test
    void a_session_that_still_resolves_publishes_nothing() throws Exception {
      resolvesTo(active());

      mvc.perform(get("/who/me").session(signedIn())).andExpect(status().isOk());

      assertThat(EVENTS).isEmpty();
    }

    @Test
    void deactivating_ends_the_session_with_identity_inactive() throws Exception {
      MockHttpSession session = signedIn();
      resolvesTo(new Resolution.Inactive(identity.id()));

      mvc.perform(get("/who/me").session(session)).andExpect(status().isUnauthorized());

      assertThat(EVENTS)
          .containsExactly(
              new SessionEnded(
                  NOW, identity.id(), credentialId, SessionEndReason.IDENTITY_INACTIVE));
    }

    @Test
    void a_credential_that_now_belongs_to_another_identity_ends_the_session_with_identity_inactive()
        throws Exception {
      Identity other = Identity.create();
      resolvesTo(new Resolution.Resolved(new WhoPrincipal(other, Set.of("task.read"))));

      mvc.perform(get("/who/me").session(signedIn())).andExpect(status().isUnauthorized());

      assertThat(EVENTS)
          .containsExactly(
              new SessionEnded(
                  NOW, identity.id(), credentialId, SessionEndReason.IDENTITY_INACTIVE));
    }

    @Test
    void revoking_the_credential_ends_the_session_with_credential_revoked() throws Exception {
      resolvesTo(new Resolution.NotLinked());

      mvc.perform(get("/who/me").session(signedIn())).andExpect(status().isUnauthorized());

      assertThat(EVENTS)
          .containsExactly(
              new SessionEnded(
                  NOW, identity.id(), credentialId, SessionEndReason.CREDENTIAL_REVOKED));
    }

    @Test
    void deleting_the_identity_ends_the_session_with_credential_revoked() throws Exception {
      resolvesTo(new Resolution.NoIdentity(identity.id()));

      mvc.perform(get("/who/me").session(signedIn())).andExpect(status().isUnauthorized());

      assertThat(EVENTS)
          .containsExactly(
              new SessionEnded(
                  NOW, identity.id(), credentialId, SessionEndReason.CREDENTIAL_REVOKED));
    }

    @Test
    void the_session_ends_only_once() throws Exception {
      MockHttpSession session = signedIn();
      resolvesTo(new Resolution.Inactive(identity.id()));

      mvc.perform(get("/who/me").session(session)).andExpect(status().isUnauthorized());
      mvc.perform(get("/who/me").session(session)).andExpect(status().isUnauthorized());

      assertThat(EVENTS).hasSize(1);
    }
  }
}
