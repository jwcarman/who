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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.spring.security.web.WhoErrorResolver;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SessionsControllerTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);

  private final MutableClock clock = new MutableClock(NOW);
  private final List<WhoEvent> events = new ArrayList<>();
  private final WhoSessions sessions = InMemorySessionRecords.sessions(clock, events::add);
  private final WhoService whoService = mock(WhoService.class);
  private final WhoSessionSecurityContextRepository repository =
      new WhoSessionSecurityContextRepository(whoService, sessions, clock, events::add, _ -> false);
  private final JsonMapper json = JsonMapper.builder().build();
  private final LogoutHandler chainLogout =
      SessionEndpoints.signOutHandler(
          new LogoutConfigurer<HttpSecurity>()
              .addLogoutHandler(
                  (request, response, authentication) ->
                      repository.onLogout(request, authentication)));
  private final AtomicInteger chainLogouts = new AtomicInteger();
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SessionsController(sessions, repository, clock, RECENT))
          .setHandlerExceptionResolvers(new WhoErrorResolver(WhoPaths.defaults()))
          .build();

  private final Identity alice = Identity.create();
  private final Identity bob = Identity.create();

  @BeforeEach
  void resolveCredentials() {
    // each browser signs in with a credential whose id is its identity's
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenAnswer(
            call -> {
              Identity identity =
                  call.<Credential>getArgument(0).id().equals(alice.id()) ? alice : bob;
              return new Resolution.Resolved(new WhoPrincipal(identity, Set.of()));
            });
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  /** A browser of {@code identity} that signed in at {@code at} with the given user agent. */
  private HttpSession browser(Identity identity, Instant at, String userAgent) {
    clock.set(at);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("User-Agent", userAgent);
    repository.saveContext(context(identity), request, new MockHttpServletResponse());
    clock.set(NOW);
    return request.getSession(false);
  }

  private static SecurityContext context(Identity identity) {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), identity.id(), SignInMethod.PASSKEY));
    return context;
  }

  private static UUID idOf(HttpSession session) {
    return ((WhoSession)
            session.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .sessionId();
  }

  /** Sends a request from {@code session}, signed in as its identity. */
  private MockHttpServletResponse send(String method, String uri, HttpSession session)
      throws Exception {
    return send(request(HttpMethod.valueOf(method), uri), session);
  }

  /** Sends {@code request} from {@code session}, authenticated as the session loads. */
  private MockHttpServletResponse send(MockHttpServletRequestBuilder request, HttpSession session)
      throws Exception {
    if (session != null) {
      // loaded on the request itself, as the chain's SecurityContextHolderFilter does
      request
          .session((MockHttpSession) session)
          .with(
              loading -> {
                SecurityContextHolder.setContext(repository.loadDeferredContext(loading).get());
                return loading;
              });
    }
    return perform(request);
  }

  /** Sends {@code request} as it is, with the chain's logout handed to it as ChainLogout does. */
  private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
    LogoutHandler counted =
        (req, res, authentication) -> {
          chainLogouts.incrementAndGet();
          chainLogout.logout(req, res, authentication);
        };
    return mvc.perform(request.requestAttr(ChainLogout.ATTRIBUTE, counted))
        .andReturn()
        .getResponse();
  }

  @Test
  void lists_the_callers_live_sessions_newest_first_with_current_marked() throws Exception {
    HttpSession older =
        browser(alice, NOW.minusSeconds(120), "Mozilla/5.0 (Windows NT 10.0) Firefox/132.0");
    HttpSession newer =
        browser(alice, NOW.minusSeconds(60), "Mozilla/5.0 (Macintosh) Version/17.0 Safari/605.1");
    browser(bob, NOW.minusSeconds(30), "curl/8.0");

    MockHttpServletResponse response = send("GET", "/who/me/sessions", older);

    assertThat(response.getStatus()).isEqualTo(200);
    List<Map<String, Object>> list =
        json.readValue(
            response.getContentAsString(),
            json.getTypeFactory().constructCollectionType(List.class, Map.class));
    assertThat(list)
        .extracting(m -> m.get("id"))
        .containsExactly(idOf(newer).toString(), idOf(older).toString());
    assertThat(list).extracting(m -> m.get("current")).containsExactly(false, true);
    assertThat(list.get(1))
        .containsEntry("device", "Firefox on Windows")
        .containsEntry("userAgent", "Mozilla/5.0 (Windows NT 10.0) Firefox/132.0")
        .containsEntry("signInMethod", "passkey")
        .containsEntry("signedInAt", NOW.minusSeconds(120).toString())
        .containsEntry("lastSeenAt", NOW.minusSeconds(120).toString());
  }

  @Test
  void never_shows_the_servlet_session_id() throws Exception {
    // a mock session's own id is a small number, which any UUID might contain
    MockHttpServletRequest signIn = new MockHttpServletRequest();
    signIn.setSession(new MockHttpSession(null, "servlet-session-" + UUID.randomUUID()));
    repository.saveContext(context(alice), signIn, new MockHttpServletResponse());
    HttpSession session = signIn.getSession(false);

    MockHttpServletResponse response = send("GET", "/who/me/sessions", session);

    assertThat(response.getContentAsString()).doesNotContain(session.getId());
  }

  @Test
  void ending_another_session_needs_a_recent_sign_in() throws Exception {
    HttpSession stale = browser(alice, NOW.minus(RECENT).minusSeconds(1), "a");
    HttpSession other = browser(alice, NOW, "b");

    MockHttpServletResponse response = send("DELETE", "/who/me/sessions/" + idOf(other), stale);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(sessions.findLive(idOf(other))).isPresent();
  }

  @Test
  void ending_another_session_ends_it() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");
    HttpSession other = browser(alice, NOW, "b");
    events.clear();

    MockHttpServletResponse response = send("DELETE", "/who/me/sessions/" + idOf(other), mine);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(sessions.findLive(idOf(other))).isEmpty();
    assertThat(sessions.findLive(idOf(mine))).isPresent();
    assertThat(events).hasExactlyElementsOfTypes(SessionEnded.class);
  }

  @Test
  void ending_the_current_session_signs_out_without_recency() throws Exception {
    HttpSession stale = browser(alice, NOW.minus(RECENT).minusSeconds(1), "a");
    UUID id = idOf(stale);
    events.clear();

    MockHttpServletResponse response = send("DELETE", "/who/me/sessions/" + id, stale);

    assertThat(response.getStatus()).isEqualTo(204);
    assertThat(sessions.findLive(id)).isEmpty();
    assertThat(((MockHttpSession) stale).isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(events).hasExactlyElementsOfTypes(SignedOut.class);
  }

  @Test
  void another_identitys_session_is_404() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");
    HttpSession bobs = browser(bob, NOW, "b");
    events.clear();

    MockHttpServletResponse response = send("DELETE", "/who/me/sessions/" + idOf(bobs), mine);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getHeader(WhoExceptionHandoff.HEADER)).isEqualTo("session-not-found");
    assertThat(sessions.findLive(idOf(bobs))).isPresent();
    assertThat(events).isEmpty();
  }

  @Test
  void an_unknown_or_malformed_id_is_404() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    for (String id : List.of(UUID.randomUUID().toString(), "not-an-id")) {
      MockHttpServletResponse response = send("DELETE", "/who/me/sessions/" + id, mine);
      assertThat(response.getStatus()).as(id).isEqualTo(404);
      assertThat(response.getHeader(WhoExceptionHandoff.HEADER)).isEqualTo("session-not-found");
    }
  }

  @Test
  void ending_all_others_keeps_the_current() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");
    HttpSession second = browser(alice, NOW, "b");
    HttpSession third = browser(alice, NOW, "c");
    HttpSession bobs = browser(bob, NOW, "d");

    MockHttpServletResponse response =
        send(delete("/who/me/sessions").param("others", "true"), mine);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(json.readTree(response.getContentAsString()).get("ended").asInt()).isEqualTo(2);
    assertThat(sessions.findLive(idOf(mine))).isPresent();
    assertThat(sessions.findLive(idOf(second))).isEmpty();
    assertThat(sessions.findLive(idOf(third))).isEmpty();
    assertThat(sessions.findLive(idOf(bobs))).isPresent();
  }

  @Test
  void ending_all_without_others_is_400() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    assertThat(send("DELETE", "/who/me/sessions", mine).getStatus()).isEqualTo(400);
    assertThat(sessions.findLive(idOf(mine))).isPresent();
  }

  @Test
  void old_path_is_not_served() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    assertThat(send("GET", "/who/sessions", mine).getStatus()).isEqualTo(404);
  }

  @Test
  void signed_out_is_401() throws Exception {
    assertThat(send("GET", "/who/me/sessions", null).getStatus()).isEqualTo(401);
  }

  @Test
  void an_api_key_caller_is_403() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), UUID.randomUUID(), SignInMethod.API_KEY));
    MockHttpServletResponse response = perform(get("/who/me/sessions"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
  }

  @Test
  void an_api_key_sent_with_a_browser_session_is_403() throws Exception {
    // on a mixed chain the key's authentication replaces the session's, but the cookie still rides
    MockHttpSession session = (MockHttpSession) browser(alice, NOW, "a");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), UUID.randomUUID(), SignInMethod.API_KEY));

    MockHttpServletResponse response = perform(get("/who/me/sessions").session(session));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
  }

  @Test
  void ending_all_others_needs_a_recent_sign_in() throws Exception {
    HttpSession stale = browser(alice, NOW.minus(RECENT).minusSeconds(1), "a");
    HttpSession other = browser(alice, NOW, "b");

    assertThat(send(delete("/who/me/sessions").param("others", "true"), stale).getStatus())
        .isEqualTo(401);
    assertThat(sessions.findLive(idOf(other))).isPresent();
  }

  @Test
  void a_token_not_loaded_from_the_browser_session_is_403() throws Exception {
    MockHttpSession session = (MockHttpSession) browser(alice, NOW, "a");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), alice.id(), SignInMethod.PASSKEY));

    MockHttpServletResponse response = perform(get("/who/me/sessions").session(session));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
  }

  @Test
  void a_session_that_changed_hands_after_loading_is_403() throws Exception {
    // another request in the same browser signed bob in between loading and answering
    HttpSession shared = browser(alice, NOW, "a");
    HttpSession bobs = browser(bob, NOW, "b");
    HttpSession alicesOther = browser(alice, NOW, "c");

    MockHttpServletResponse response =
        perform(
            delete("/who/me/sessions")
                .param("others", "true")
                .session((MockHttpSession) shared)
                .with(
                    loading -> {
                      SecurityContextHolder.setContext(
                          repository.loadDeferredContext(loading).get());
                      shared.setAttribute(
                          WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
                          bobs.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE));
                      return loading;
                    }));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(sessions.findLive(idOf(alicesOther))).isPresent();
  }

  @Test
  void a_session_without_a_record_is_signed_out() throws Exception {
    MockHttpSession unrecorded = new MockHttpSession();
    unrecorded.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(alice.id(), alice.id(), NOW, SignInMethod.PASSKEY));

    assertThat(send("GET", "/who/me/sessions", unrecorded).getStatus()).isEqualTo(401);
  }

  @Test
  void an_error_is_whos_problem() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    MockHttpServletResponse response =
        send("DELETE", "/who/me/sessions/" + UUID.randomUUID(), mine);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    JsonNode body = json.readTree(response.getContentAsString());
    assertThat(body.get("code").asString()).isEqualTo("session-not-found");
    assertThat(body.get("type").asString()).isEqualTo("urn:who:error:session-not-found");
  }

  @Test
  void ending_all_without_others_is_an_invalid_request() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    MockHttpServletResponse response = send("DELETE", "/who/me/sessions", mine);

    assertThat(response.getHeader("Who-Error")).isEqualTo("invalid-request");
    assertThat(json.readTree(response.getContentAsString()).get("code").asString())
        .isEqualTo("invalid-request");
  }

  @Test
  void a_bearer_token_is_refused() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), UUID.randomUUID(), SignInMethod.JWT));

    MockHttpServletResponse response = perform(get("/who/me/sessions"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
  }

  @Test
  void ending_your_own_session_runs_the_chains_logout() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    send("DELETE", "/who/me/sessions/" + idOf(mine), mine);

    assertThat(chainLogouts).hasValue(1);
  }

  @Test
  void ending_another_session_does_not_sign_this_one_out() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");
    HttpSession other = browser(alice, NOW, "b");

    send("DELETE", "/who/me/sessions/" + idOf(other), mine);

    assertThat(chainLogouts).hasValue(0);
  }

  @Test
  void the_answers_are_the_records_themselves() throws NoSuchMethodException {
    // Spring registers a handler's return type for native images; a void handler hides it
    assertThat(SessionsController.class.getMethod("list", HttpServletRequest.class).getReturnType())
        .isEqualTo(List.class);
    assertThat(
            SessionsController.class
                .getMethod("endOthers", String.class, HttpServletRequest.class)
                .getReturnType())
        .isEqualTo(EndedSessions.class);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void set(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
