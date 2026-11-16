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
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SessionEnded;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SessionsFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);

  private final MutableClock clock = new MutableClock(NOW);
  private final List<WhoEvent> events = new ArrayList<>();
  private final WhoSessions sessions = InMemorySessionRecords.sessions(clock, events::add);
  private final WhoSessionSecurityContextRepository repository =
      new WhoSessionSecurityContextRepository(mock(WhoService.class), sessions, clock, events::add);
  private final JsonMapper json = JsonMapper.builder().build();
  private final SessionsFilter filter =
      new SessionsFilter(sessions, repository, new WhoExceptionHandoff(null), json, clock, RECENT);

  private final Identity alice = Identity.create();
  private final Identity bob = Identity.create();
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);

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
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    if (session != null) {
      request.setSession(session);
      WhoSession who =
          (WhoSession) session.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new WhoAuthenticationToken(
                  new WhoPrincipal(who.identityId().equals(alice.id()) ? alice : bob, Set.of()),
                  who.credentialId(),
                  SignInMethod.PASSKEY));
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void lists_the_callers_live_sessions_newest_first_with_current_marked() throws Exception {
    HttpSession older =
        browser(alice, NOW.minusSeconds(120), "Mozilla/5.0 (Windows NT 10.0) Firefox/132.0");
    HttpSession newer =
        browser(alice, NOW.minusSeconds(60), "Mozilla/5.0 (Macintosh) Version/17.0 Safari/605.1");
    browser(bob, NOW.minusSeconds(30), "curl/8.0");

    MockHttpServletResponse response = send("GET", "/who/sessions", older);

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
    assertThat(reachedChain).isFalse();
  }

  @Test
  void never_shows_the_servlet_session_id() throws Exception {
    // a mock session's own id is a small number, which any UUID might contain
    MockHttpServletRequest signIn = new MockHttpServletRequest();
    signIn.setSession(new MockHttpSession(null, "servlet-session-" + UUID.randomUUID()));
    repository.saveContext(context(alice), signIn, new MockHttpServletResponse());
    HttpSession session = signIn.getSession(false);

    MockHttpServletResponse response = send("GET", "/who/sessions", session);

    assertThat(response.getContentAsString()).doesNotContain(session.getId());
  }

  @Test
  void ending_another_session_needs_a_recent_sign_in() throws Exception {
    HttpSession stale = browser(alice, NOW.minus(RECENT).minusSeconds(1), "a");
    HttpSession other = browser(alice, NOW, "b");

    MockHttpServletResponse response = send("DELETE", "/who/sessions/" + idOf(other), stale);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(sessions.findLive(idOf(other))).isPresent();
  }

  @Test
  void ending_another_session_ends_it() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");
    HttpSession other = browser(alice, NOW, "b");
    events.clear();

    MockHttpServletResponse response = send("DELETE", "/who/sessions/" + idOf(other), mine);

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

    MockHttpServletResponse response = send("DELETE", "/who/sessions/" + id, stale);

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

    MockHttpServletResponse response = send("DELETE", "/who/sessions/" + idOf(bobs), mine);

    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(sessions.findLive(idOf(bobs))).isPresent();
    assertThat(events).isEmpty();
  }

  @Test
  void an_unknown_or_malformed_id_is_404() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");

    assertThat(send("DELETE", "/who/sessions/" + UUID.randomUUID(), mine).getStatus())
        .isEqualTo(404);
    assertThat(send("DELETE", "/who/sessions/not-an-id", mine).getStatus()).isEqualTo(404);
  }

  @Test
  void ending_all_others_keeps_the_current() throws Exception {
    HttpSession mine = browser(alice, NOW, "a");
    HttpSession second = browser(alice, NOW, "b");
    HttpSession third = browser(alice, NOW, "c");
    HttpSession bobs = browser(bob, NOW, "d");

    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/who/sessions");
    request.setParameter("others", "true");
    request.setSession(mine);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), alice.id(), SignInMethod.PASSKEY));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);

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

    assertThat(send("DELETE", "/who/sessions", mine).getStatus()).isEqualTo(400);
    assertThat(sessions.findLive(idOf(mine))).isPresent();
  }

  @Test
  void signed_out_is_401() throws Exception {
    assertThat(send("GET", "/who/sessions", null).getStatus()).isEqualTo(401);
  }

  @Test
  void an_api_key_caller_is_403() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), UUID.randomUUID(), SignInMethod.API_KEY));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest("GET", "/who/sessions"), response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void other_paths_pass_through() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest("GET", "/who/me"), response, chain);

    assertThat(reachedChain).isTrue();
  }

  @Test
  void ending_all_others_needs_a_recent_sign_in() throws Exception {
    HttpSession stale = browser(alice, NOW.minus(RECENT).minusSeconds(1), "a");
    HttpSession other = browser(alice, NOW, "b");

    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/who/sessions");
    request.setParameter("others", "true");
    request.setSession(stale);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), alice.id(), SignInMethod.PASSKEY));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(sessions.findLive(idOf(other))).isPresent();
  }

  @Test
  void a_session_of_someone_else_than_the_caller_is_403() throws Exception {
    HttpSession bobs = browser(bob, NOW, "b");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/who/sessions");
    request.setSession(bobs);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(alice, Set.of()), alice.id(), SignInMethod.PASSKEY));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  void a_session_without_a_record_is_403() throws Exception {
    MockHttpSession unrecorded = new MockHttpSession();
    unrecorded.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(alice.id(), alice.id(), NOW, SignInMethod.PASSKEY));

    assertThat(send("GET", "/who/sessions", unrecorded).getStatus()).isEqualTo(403);
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
