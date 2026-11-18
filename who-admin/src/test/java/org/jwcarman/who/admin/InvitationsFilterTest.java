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
package org.jwcarman.who.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailAddressTakenException;
import org.jwcarman.who.emaillink.EmailLinkOutcome;
import org.jwcarman.who.emaillink.InvalidInvitationException;
import org.jwcarman.who.emaillink.Invitation;
import org.jwcarman.who.emaillink.InvitationService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.bind.annotation.ResponseStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InvitationsFilterTest {

  private static final JsonMapper JSON = new JsonMapper();
  private static final String MANAGE = WhoAdminPermissions.IDENTITIES_MANAGE;
  private static final String HEADER = WhoExceptionHandoff.HEADER;
  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);
  private static final String PATH = "/who/invitations";
  private static final String ADDRESS = "carol@example.com";

  /** A clock a test can move. */
  private static final class MutableClock extends Clock {

    private Instant now = NOW;

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

  private final InvitationService invitations = mock(InvitationService.class);
  private final RbacService rbac = mock(RbacService.class);
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);
  private final MutableClock clock = new MutableClock();
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
  private final Set<String> granted = new HashSet<>();
  private final WhoService whoService = new WhoService(identities, links, i -> Set.copyOf(granted));
  private final WhoSessionSecurityContextRepository sessions =
      new WhoSessionSecurityContextRepository(
          whoService,
          InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
          clock,
          WhoEventPublisher.NOOP,
          request ->
              request.getHeader("X-API-Key") != null || request.getHeader("Authorization") != null);
  private final Role editor = new Role(UUID.randomUUID(), "editor");
  private final Role member = new Role(UUID.randomUUID(), "member");
  private final UUID invited = UUID.randomUUID();
  private final Identity caller = Identity.create();
  private HttpSession browser;
  private InvitationsFilter filter;

  @BeforeEach
  void setUp() {
    when(identities.findById(caller.id())).thenReturn(Optional.of(caller));
    when(rbac.findRole("editor")).thenReturn(Optional.of(editor));
    when(rbac.findPermissions(editor)).thenReturn(Set.of("task.read", "task.write"));
    when(rbac.findRole("member")).thenReturn(Optional.of(member));
    when(rbac.findPermissions(member)).thenReturn(Set.of());
    when(rbac.findRole("ghost")).thenReturn(Optional.empty());
    when(invitations.invite(anyString(), any()))
        .thenReturn(new Invitation(invited, EmailLinkOutcome.SENT));
    WhoExceptionHandoff handoff = new WhoExceptionHandoff(WhoPaths.defaults(), null);
    filter =
        new InvitationsFilter(
            WhoPaths.defaults(),
            invitations,
            rbac,
            new AdminGate(sessions, handoff, clock, RECENT),
            JSON);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void signIn(SignInMethod method, String... permissions) {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(caller, Set.of(permissions)), UUID.randomUUID(), method));
    SecurityContextHolder.setContext(context);
  }

  /** Signs in with a bearer token carrying {@code permissions}. */
  private void signIn(String... permissions) {
    signIn(SignInMethod.JWT, permissions);
  }

  /** Signs the caller in as a browser at {@code at}, holding {@code permissions}. */
  private void browserSignIn(Instant at, String... permissions) {
    granted.addAll(Set.of(permissions));
    UUID credential = UUID.randomUUID();
    when(links.findIdentityIdByCredentialId(credential)).thenReturn(Optional.of(caller.id()));
    clock.now = at;
    MockHttpServletRequest request = new MockHttpServletRequest();
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(caller, Set.of(permissions)), credential, SignInMethod.EMAIL_LINK));
    sessions.saveContext(context, request, new MockHttpServletResponse());
    clock.now = NOW;
    browser = request.getSession(false);
  }

  private MockHttpServletResponse send(String method, String uri, String body) throws Exception {
    return send(method, uri, body, Map.of());
  }

  private MockHttpServletResponse send(
      String method, String uri, String body, Map<String, String> headers) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    headers.forEach(request::addHeader);
    if (body != null) {
      request.setContentType("application/json");
      request.setContent(body.getBytes(StandardCharsets.UTF_8));
    }
    if (browser != null) {
      request.setSession(browser);
      SecurityContextHolder.setContext(sessions.loadDeferredContext(request).get());
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private MockHttpServletResponse invite(String body) throws Exception {
    return send("POST", PATH, body);
  }

  private static String body(String email, String... roles) {
    StringBuilder json = new StringBuilder("{\"email\":\"").append(email).append("\",\"roles\":[");
    for (int i = 0; i < roles.length; i++) {
      json.append(i == 0 ? "" : ",").append('"').append(roles[i]).append('"');
    }
    return json.append("]}").toString();
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString());
  }

  @Nested
  class Inviting {

    @Test
    void answers_created_with_the_identity_and_whether_the_link_was_sent() throws Exception {
      signIn(MANAGE, "task.read", "task.write");

      MockHttpServletResponse response = invite(body(ADDRESS, "editor"));

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(response.getContentType()).startsWith("application/json");
      assertThat(json(response).get("identityId").asString()).isEqualTo(invited.toString());
      assertThat(json(response).get("linkSent").asBoolean()).isTrue();
      verify(invitations).invite(ADDRESS, Set.of("editor"));
      assertThat(reachedChain).isFalse();
    }

    @Test
    void roles_may_be_left_out() throws Exception {
      signIn(MANAGE);

      assertThat(invite("{\"email\":\"" + ADDRESS + "\"}").getStatus()).isEqualTo(201);
      verify(invitations).invite(ADDRESS, Set.of());
    }

    @Test
    void a_link_that_was_not_sent_still_answers_created() throws Exception {
      signIn(MANAGE);
      when(invitations.invite(anyString(), any()))
          .thenReturn(new Invitation(invited, EmailLinkOutcome.SEND_FAILED));

      MockHttpServletResponse response = invite(body(ADDRESS));

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(json(response).get("linkSent").asBoolean()).isFalse();
    }

    @Test
    void a_taken_address_is_a_conflict() throws Exception {
      signIn(MANAGE);
      when(invitations.invite(anyString(), any())).thenThrow(new EmailAddressTakenException());

      MockHttpServletResponse response = invite(body(ADDRESS));

      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(response.getHeader(HEADER)).isEqualTo("email-address-taken");
    }

    @Test
    void a_listeners_own_unique_key_violation_is_not_a_taken_address() throws Exception {
      // the service reports its own address's unique key as an EmailAddressTakenException, so any
      // other DuplicateKeyException is the application's, from its IdentityInvited listener
      signIn(MANAGE);
      when(invitations.invite(anyString(), any()))
          .thenThrow(new DuplicateKeyException("app_profile_key"));

      MockHttpServletResponse response = invite(body(ADDRESS));

      assertThat(response.getStatus()).isEqualTo(500);
      assertThat(response.getHeader(HEADER)).isEqualTo("server-error");
    }

    @Test
    void a_listener_refusal_carrying_its_own_status_is_answered_with_it() throws Exception {
      signIn(MANAGE);
      when(invitations.invite(anyString(), any())).thenThrow(new Unprocessable());

      MockHttpServletResponse response = invite(body(ADDRESS));

      assertThat(response.getStatus()).isEqualTo(422);
    }

    @Test
    void any_other_failure_is_answered_by_who_as_a_server_error() throws Exception {
      List<Exception> handedOff = new ArrayList<>();
      filter =
          new InvitationsFilter(
              WhoPaths.defaults(),
              invitations,
              rbac,
              new AdminGate(
                  sessions,
                  new WhoExceptionHandoff(WhoPaths.defaults(), (req, res, e) -> !handedOff.add(e)),
                  clock,
                  RECENT),
              JSON);
      signIn(MANAGE);
      IllegalStateException failure = new IllegalStateException("the listener failed");
      when(invitations.invite(anyString(), any())).thenThrow(failure);

      MockHttpServletResponse response = invite(body(ADDRESS));

      // under who's base path the application's handlers are not asked
      assertThat(handedOff).isEmpty();
      assertThat(response.getStatus()).isEqualTo(500);
      assertThat(response.getHeader(HEADER)).isEqualTo("server-error");
      assertThat(response.getContentAsString()).doesNotContain("the listener failed");
    }

    @Test
    void an_address_the_service_refuses_is_bad_request() throws Exception {
      signIn(MANAGE);
      when(invitations.invite(anyString(), any()))
          .thenThrow(new InvalidInvitationException("email must be an email address"));

      MockHttpServletResponse response = invite(body("not-an-address"));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-request");
    }
  }

  /** What an application's {@code IdentityInvited} listener might throw to refuse an invitation. */
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  static final class Unprocessable extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;
  }

  @Nested
  class Authorization {

    @Test
    void signed_out_is_unauthorized() throws Exception {
      MockHttpServletResponse response = invite(body(ADDRESS));

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(HEADER)).isEqualTo("not-signed-in");
      verifyNoInteractions(invitations);
    }

    @Test
    void endpoint_needs_manage_permission() throws Exception {
      signIn(WhoAdminPermissions.IDENTITIES_READ);

      MockHttpServletResponse response = invite(body(ADDRESS));

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader(HEADER)).isEqualTo("permission-required");
      verifyNoInteractions(invitations);
    }

    @Test
    void permission_is_checked_before_the_body() throws Exception {
      signIn();

      assertThat(invite("nope").getStatus()).isEqualTo(403);
    }

    @Test
    void a_stale_browser_must_sign_in_again() throws Exception {
      browserSignIn(NOW.minus(RECENT).minusSeconds(1), MANAGE);

      MockHttpServletResponse response = invite("nope");

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(HEADER)).isEqualTo("reauthentication-required");
      verifyNoInteractions(invitations);
    }

    @Test
    void a_stale_browser_without_the_permission_is_forbidden() throws Exception {
      browserSignIn(NOW.minus(RECENT).minusSeconds(1));

      assertThat(invite(body(ADDRESS)).getStatus()).isEqualTo(403);
    }

    @Test
    void a_fresh_browser_may_invite() throws Exception {
      browserSignIn(NOW.minusSeconds(60), MANAGE);

      assertThat(invite(body(ADDRESS)).getStatus()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-API-Key: junk", "Authorization: Bearer junk"})
    void a_stale_browser_with_an_ignored_credential_header_cannot_invite(String header)
        throws Exception {
      browserSignIn(NOW.minus(RECENT).minusSeconds(1), MANAGE);
      String[] nameAndValue = header.split(": ", 2);

      MockHttpServletResponse response =
          send("POST", PATH, body(ADDRESS), Map.of(nameAndValue[0], nameAndValue[1]));

      assertThat(response.getStatus()).isIn(401, 403);
      verifyNoInteractions(invitations);
    }

    @Test
    void a_scoped_api_key_may_invite() throws Exception {
      signIn(SignInMethod.API_KEY, MANAGE);

      assertThat(invite(body(ADDRESS, "member")).getStatus()).isEqualTo(201);
      verify(invitations).invite(ADDRESS, Set.of("member"));
    }
  }

  @Nested
  class Role_grants {

    @Test
    void a_role_beyond_the_callers_permissions_is_forbidden() throws Exception {
      signIn(MANAGE, "task.read");

      MockHttpServletResponse response = invite(body(ADDRESS, "member", "editor"));

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader(HEADER)).isEqualTo("role-grant-forbidden");
      verifyNoInteractions(invitations);
    }

    @Test
    void an_api_key_grants_only_within_its_scopes() throws Exception {
      // the key's owner holds every permission; the key is scoped to manage alone
      granted.addAll(Set.of(MANAGE, "task.read", "task.write"));
      signIn(SignInMethod.API_KEY, MANAGE);

      MockHttpServletResponse response = invite(body(ADDRESS, "editor"));

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader(HEADER)).isEqualTo("role-grant-forbidden");
      verifyNoInteractions(invitations);
    }

    @Test
    void a_role_with_no_permissions_may_be_granted() throws Exception {
      signIn(MANAGE);

      assertThat(invite(body(ADDRESS, "member")).getStatus()).isEqualTo(201);
    }

    @Test
    void an_unknown_role_is_bad_request_even_beyond_the_callers_permissions() throws Exception {
      signIn(MANAGE);

      MockHttpServletResponse response = invite(body(ADDRESS, "editor", "ghost"));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-request");
      verify(invitations, never()).invite(anyString(), any());
    }
  }

  @Nested
  class Bad_requests {

    @BeforeEach
    void signedIn() {
      signIn(MANAGE);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "nope",
          "[]",
          "{}",
          "{\"email\":42}",
          "{\"email\":null}",
          "{\"email\":\"carol@example.com\",\"roles\":\"editor\"}",
          "{\"email\":\"carol@example.com\",\"roles\":[42]}",
          "{\"email\":\"carol@example.com\",\"roles\":[null]}"
        })
    void a_malformed_body_is_bad_request(String body) throws Exception {
      MockHttpServletResponse response = invite(body);

      assertThat(response.getStatus()).as(body).isEqualTo(400);
      verifyNoInteractions(invitations);
    }

    @Test
    void a_missing_body_is_bad_request() throws Exception {
      assertThat(send("POST", PATH, null).getStatus()).isEqualTo(400);
      verifyNoInteractions(invitations);
    }

    @Test
    void an_oversized_body_is_bad_request() throws Exception {
      String big = "{\"email\":\"" + "a".repeat(AdminJson.MAX_BODY_BYTES) + "\"}";

      assertThat(invite(big).getStatus()).isEqualTo(400);
      verifyNoInteractions(invitations);
    }
  }

  @Test
  void other_requests_pass_through() throws Exception {
    signIn(MANAGE);

    for (String method : new String[] {"GET", "PUT", "DELETE"}) {
      reachedChain.set(false);
      send(method, PATH, null);
      assertThat(reachedChain).as(method).isTrue();
    }
    reachedChain.set(false);
    send("POST", "/invitations", body(ADDRESS));
    assertThat(reachedChain).isTrue();
    verifyNoInteractions(invitations);
  }
}
