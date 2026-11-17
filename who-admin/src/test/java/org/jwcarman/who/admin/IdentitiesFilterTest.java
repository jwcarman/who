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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.SignInCredentialCounter;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IdentitiesFilterTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final JsonMapper JSON = new JsonMapper();
  private static final String READ = WhoAdminPermissions.IDENTITIES_READ;
  private static final String MANAGE = WhoAdminPermissions.IDENTITIES_MANAGE;
  private static final String HEADER = WhoExceptionHandoff.HEADER;
  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);

  /** The permissions the admin and editor roles carry. */
  private static final String TASK_READ = "task.read";

  private static final String TASK_WRITE = "task.write";

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

  /** Identities, in memory, paged as the JDBC repository pages them. */
  private static final class Identities implements IdentityRepository {

    private final Map<UUID, Identity> byId = new LinkedHashMap<>();

    private List<Identity> matching(Optional<IdentityStatus> status, Optional<Set<UUID>> ids) {
      return byId.values().stream()
          .filter(i -> status.map(s -> s == i.status()).orElse(true))
          .filter(i -> ids.map(set -> set.contains(i.id())).orElse(true))
          .sorted(Comparator.comparing(Identity::createdAt).thenComparing(Identity::id))
          .toList();
    }

    @Override
    public Optional<Identity> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Identity> lockById(UUID id) {
      return findById(id);
    }

    @Override
    public List<Identity> findPage(
        Optional<IdentityStatus> status, Optional<Set<UUID>> ids, int page, int size) {
      return matching(status, ids).stream().skip((long) page * size).limit(size).toList();
    }

    @Override
    public long count(Optional<IdentityStatus> status, Optional<Set<UUID>> ids) {
      return matching(status, ids).size();
    }

    @Override
    public Identity save(Identity identity) {
      byId.put(identity.id(), identity);
      return identity;
    }

    @Override
    public boolean existsById(UUID id) {
      return byId.containsKey(id);
    }

    @Override
    public void deleteById(UUID id) {
      byId.remove(id);
    }
  }

  /** Addresses, in memory. */
  private static final class Emails implements IdentityEmails {

    private final Map<String, UUID> owners = new LinkedHashMap<>();

    @Override
    public Optional<UUID> findIdentityId(String address) {
      return Optional.ofNullable(owners.get(address.trim().toLowerCase()));
    }

    @Override
    public List<String> addressesOf(UUID identityId) {
      return owners.entrySet().stream()
          .filter(e -> e.getValue().equals(identityId))
          .map(Map.Entry::getKey)
          .toList();
    }
  }

  private final Identities identities = new Identities();
  private final Emails emails = new Emails();
  private final RbacService rbac = mock(RbacService.class);
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);
  private final Map<UUID, Set<Role>> held = new LinkedHashMap<>();
  private final Role admin = new Role(UUID.randomUUID(), "admin");
  private final Role editor = new Role(UUID.randomUUID(), "editor");
  private final MutableClock clock = new MutableClock();
  private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
  private final Set<String> granted = new HashSet<>();
  private final WhoService whoService = new WhoService(identities, links, i -> Set.copyOf(granted));
  private final WhoSessionSecurityContextRepository sessions =
      new WhoSessionSecurityContextRepository(
          whoService,
          InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
          clock,
          WhoEventPublisher.NOOP);
  private Identity caller;
  private HttpSession browser;
  private IdentitiesFilter filter;

  private Identity identity(IdentityStatus status, Instant created) {
    return identities.save(new Identity(UUID.randomUUID(), status, created, created));
  }

  @BeforeEach
  void setUp() {
    caller = identity(IdentityStatus.ACTIVE, T0);
    for (Role role : List.of(admin, editor)) {
      when(rbac.findRole(role.name())).thenReturn(Optional.of(role));
      when(rbac.findPermissions(role)).thenReturn(Set.of(TASK_WRITE, TASK_READ));
    }
    when(rbac.listRoles()).thenReturn(List.of(admin, editor));
    when(rbac.findRolesForIdentity(any()))
        .thenAnswer(
            call ->
                held.getOrDefault(call.<UUID>getArgument(0), Set.of()).stream()
                    .sorted(Comparator.comparing(Role::name))
                    .toList());
    when(rbac.findIdentityIdsWithRole(any()))
        .thenAnswer(
            call ->
                held.entrySet().stream()
                    .filter(e -> e.getValue().contains(call.<Role>getArgument(0)))
                    .map(Map.Entry::getKey)
                    .toList());
    doAnswer(
            call -> {
              held.computeIfAbsent(call.<Identity>getArgument(0).id(), id -> new HashSet<>())
                  .add(call.getArgument(1));
              return null;
            })
        .when(rbac)
        .assignRoleToIdentity(any(), any());
    doAnswer(
            call -> {
              Set<Role> roles = held.getOrDefault(call.<Identity>getArgument(0).id(), Set.of());
              if (!roles.contains(call.<Role>getArgument(1))) {
                throw new IllegalArgumentException("not assigned");
              }
              roles.remove(call.<Role>getArgument(1));
              return null;
            })
        .when(rbac)
        .removeRoleFromIdentity(any(), any());
    filter = filter(emails, guard());
  }

  private LastCredentialGuard guard() {
    SignInCredentialCounter passkeys =
        new SignInCredentialCounter() {
          @Override
          public String kind() {
            return "passkey";
          }

          @Override
          public long count(UUID identityId) {
            return 2;
          }
        };
    return new LastCredentialGuard(
        identities,
        List.of(passkeys),
        new TransactionTemplate(mock(PlatformTransactionManager.class)));
  }

  private IdentitiesFilter filter(IdentityEmails withEmails, LastCredentialGuard withGuard) {
    return new IdentitiesFilter(
        WhoPaths.defaults(),
        identities,
        whoService,
        rbac,
        withEmails,
        withGuard,
        new AdminGate(sessions, new WhoExceptionHandoff(null), clock, RECENT),
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
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    int query = uri.indexOf('?');
    if (query >= 0) {
      request.setRequestURI(uri.substring(0, query));
      request.setQueryString(uri.substring(query + 1));
      for (String pair : uri.substring(query + 1).split("&")) {
        String[] kv = pair.split("=", 2);
        request.addParameter(kv[0], kv.length > 1 ? kv[1] : "");
      }
    }
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

  private MockHttpServletResponse get(String uri) throws Exception {
    return send("GET", uri, null);
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString());
  }

  private static List<String> ids(JsonNode page) {
    return page.get("items").valueStream().map(item -> item.get("id").asString()).toList();
  }

  @Nested
  class Signed_out {

    @Test
    void is_unauthorized() throws Exception {
      MockHttpServletResponse response = get("/who/identities");

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(HEADER)).isEqualTo("not-signed-in");
      assertThat(reachedChain).isFalse();
    }
  }

  @Nested
  class Permissions {

    @Test
    void without_read_permission_is_forbidden() throws Exception {
      signIn(MANAGE);

      for (String uri :
          List.of("/who/identities", "/who/identities/" + caller.id(), "/who/roles")) {
        MockHttpServletResponse response = get(uri);
        assertThat(response.getStatus()).as(uri).isEqualTo(403);
        assertThat(response.getHeader(HEADER)).isEqualTo("permission-required");
      }
    }

    @Test
    void without_manage_permission_changes_are_forbidden() throws Exception {
      signIn(READ);
      Identity other = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));

      assertThat(send("PATCH", "/who/identities/" + other.id(), "{\"status\":\"DISABLED\"}"))
          .extracting(MockHttpServletResponse::getStatus)
          .isEqualTo(403);
      assertThat(send("PUT", "/who/identities/" + other.id() + "/roles/admin", null).getStatus())
          .isEqualTo(403);
      assertThat(send("DELETE", "/who/identities/" + other.id() + "/roles/admin", null).getStatus())
          .isEqualTo(403);
      assertThat(identities.findById(other.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
      verify(rbac, never()).assignRoleToIdentity(any(), any());
    }

    @Test
    void permission_is_checked_before_the_identity_is_looked_up() throws Exception {
      signIn();

      assertThat(get("/who/identities/" + UUID.randomUUID()).getStatus()).isEqualTo(403);
    }

    @Test
    void scoped_api_key_may_administer() throws Exception {
      signIn(SignInMethod.API_KEY, READ, MANAGE);
      Identity other = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));

      assertThat(get("/who/identities").getStatus()).isEqualTo(200);
      assertThat(send("PATCH", "/who/identities/" + other.id(), "{\"status\":\"DISABLED\"}"))
          .extracting(MockHttpServletResponse::getStatus)
          .isEqualTo(200);
    }

    @Test
    void a_bearer_token_may_administer() throws Exception {
      signIn(SignInMethod.JWT, READ);

      assertThat(get("/who/roles").getStatus()).isEqualTo(200);
    }
  }

  @Nested
  class Listing {

    @BeforeEach
    void signedIn() {
      signIn(READ);
    }

    @Test
    void lists_identities_paged_in_creation_order() throws Exception {
      Identity second = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
      Identity third = identity(IdentityStatus.ACTIVE, T0.plusSeconds(2));

      JsonNode first = json(get("/who/identities?page=0&size=2"));
      JsonNode last = json(get("/who/identities?page=1&size=2"));

      assertThat(ids(first)).containsExactly(caller.id().toString(), second.id().toString());
      assertThat(first.get("page").asInt()).isZero();
      assertThat(first.get("size").asInt()).isEqualTo(2);
      assertThat(first.get("total").asLong()).isEqualTo(3);
      assertThat(ids(last)).containsExactly(third.id().toString());
    }

    @Test
    void pages_twenty_at_a_time_by_default() throws Exception {
      JsonNode page = json(get("/who/identities"));

      assertThat(page.get("page").asInt()).isZero();
      assertThat(page.get("size").asInt()).isEqualTo(20);
    }

    @Test
    void filters_by_status_and_role() throws Exception {
      Identity disabledAdmin = identity(IdentityStatus.DISABLED, T0.plusSeconds(1));
      Identity activeAdmin = identity(IdentityStatus.ACTIVE, T0.plusSeconds(2));
      identity(IdentityStatus.DISABLED, T0.plusSeconds(3));
      held.put(disabledAdmin.id(), new HashSet<>(Set.of(admin)));
      held.put(activeAdmin.id(), new HashSet<>(Set.of(admin)));

      assertThat(ids(json(get("/who/identities?role=admin"))))
          .containsExactly(disabledAdmin.id().toString(), activeAdmin.id().toString());
      assertThat(ids(json(get("/who/identities?status=DISABLED&role=admin"))))
          .containsExactly(disabledAdmin.id().toString());
      assertThat(json(get("/who/identities?status=DISABLED")).get("total").asLong()).isEqualTo(2);
    }

    @Test
    void a_role_nobody_holds_is_an_empty_page() throws Exception {
      JsonNode page = json(get("/who/identities?role=editor"));

      assertThat(ids(page)).isEmpty();
      assertThat(page.get("total").asLong()).isZero();
    }

    @Test
    void an_unknown_role_is_an_empty_page() throws Exception {
      when(rbac.findRole("nope")).thenReturn(Optional.empty());

      MockHttpServletResponse response = get("/who/identities?role=nope");

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(ids(json(response))).isEmpty();
      assertThat(json(response).get("total").asLong()).isZero();
    }

    @Test
    void finds_by_email_address() throws Exception {
      Identity bob = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
      emails.owners.put("bob@example.com", bob.id());

      JsonNode page = json(get("/who/identities?email=Bob@Example.com"));

      assertThat(ids(page)).containsExactly(bob.id().toString());
      assertThat(page.get("total").asLong()).isEqualTo(1);
      assertThat(page.get("items").get(0).get("emailAddresses").get(0).asString())
          .isEqualTo("bob@example.com");
    }

    @Test
    void an_address_nobody_holds_is_an_empty_page() throws Exception {
      JsonNode page = json(get("/who/identities?email=nobody@example.com"));

      assertThat(ids(page)).isEmpty();
      assertThat(page.get("total").asLong()).isZero();
    }

    @Test
    void email_and_role_together_must_both_match() throws Exception {
      Identity bob = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
      emails.owners.put("bob@example.com", bob.id());
      held.put(caller.id(), new HashSet<>(Set.of(admin)));

      assertThat(ids(json(get("/who/identities?email=bob@example.com&role=admin")))).isEmpty();
      held.put(bob.id(), new HashSet<>(Set.of(admin)));
      assertThat(ids(json(get("/who/identities?email=bob@example.com&role=admin"))))
          .containsExactly(bob.id().toString());
    }

    @Test
    void searching_by_email_without_email_sign_in_is_a_bad_request() throws Exception {
      filter = filter(null, guard());

      MockHttpServletResponse response = get("/who/identities?email=bob@example.com");

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-admin-request");
    }

    @Test
    void size_above_100_is_bad_request() throws Exception {
      MockHttpServletResponse response = get("/who/identities?size=101");

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-admin-request");
      assertThat(get("/who/identities?size=100").getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "size=0",
          "size=-1",
          "size=ten",
          "page=-1",
          "page=x",
          "page=99999999999",
          "status=INACTIVE",
          "status=active"
        })
    void a_bad_query_is_a_bad_request(String query) throws Exception {
      MockHttpServletResponse response = get("/who/identities?" + query);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-admin-request");
    }

    @Test
    void a_page_past_the_last_is_empty() throws Exception {
      JsonNode page = json(get("/who/identities?page=2147483647&size=100"));

      assertThat(ids(page)).isEmpty();
      assertThat(page.get("total").asLong()).isEqualTo(1);
    }
  }

  @Nested
  class One_identity {

    @BeforeEach
    void signedIn() {
      signIn(READ);
    }

    @Test
    void shows_its_status_roles_addresses_and_credentials() throws Exception {
      held.put(caller.id(), new HashSet<>(Set.of(editor, admin)));
      emails.owners.put("zed@example.com", caller.id());
      emails.owners.put("amy@example.com", caller.id());

      MockHttpServletResponse response = get("/who/identities/" + caller.id());

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith("application/json");
      JsonNode view = json(response);
      assertThat(view.get("id").asString()).isEqualTo(caller.id().toString());
      assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
      assertThat(Instant.parse(view.get("created").asString())).isEqualTo(T0);
      assertThat(view.get("roles").valueStream().map(JsonNode::asString))
          .containsExactly("admin", "editor");
      assertThat(view.get("emailAddresses").valueStream().map(JsonNode::asString))
          .containsExactly("amy@example.com", "zed@example.com");
      assertThat(view.get("credentials").get("passkey").asLong()).isEqualTo(2);
    }

    @Test
    void without_email_sign_in_shows_no_addresses() throws Exception {
      filter = filter(null, guard());

      JsonNode view = json(get("/who/identities/" + caller.id()));

      assertThat(view.get("emailAddresses").isEmpty()).isTrue();
    }

    @Test
    void without_a_guard_shows_no_counts() throws Exception {
      filter = filter(emails, null);

      JsonNode view = json(get("/who/identities/" + caller.id()));

      assertThat(view.get("credentials").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-000000000000", "not-a-uuid"})
    void an_unknown_identity_is_not_found(String id) throws Exception {
      MockHttpServletResponse response = get("/who/identities/" + id);

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getHeader(HEADER)).isEqualTo("identity-not-found");
    }
  }

  @Nested
  class Status {

    private Identity other;

    @BeforeEach
    void signedIn() {
      signIn(MANAGE);
      other = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
    }

    private MockHttpServletResponse patch(UUID id, String body) throws Exception {
      return send("PATCH", "/who/identities/" + id, body);
    }

    @Test
    void deactivates_and_reactivates() throws Exception {
      MockHttpServletResponse off = patch(other.id(), "{\"status\":\"DISABLED\"}");

      assertThat(off.getStatus()).isEqualTo(200);
      assertThat(json(off).get("status").asString()).isEqualTo("DISABLED");
      assertThat(identities.findById(other.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.DISABLED);

      MockHttpServletResponse on = patch(other.id(), "{\"status\":\"ACTIVE\"}");

      assertThat(on.getStatus()).isEqualTo(200);
      assertThat(json(on).get("status").asString()).isEqualTo("ACTIVE");
      assertThat(json(on).get("id").asString()).isEqualTo(other.id().toString());
    }

    @Test
    void cannot_deactivate_oneself() throws Exception {
      MockHttpServletResponse response = patch(caller.id(), "{\"status\":\"DISABLED\"}");

      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(response.getHeader(HEADER)).isEqualTo("self-deactivation");
      assertThat(identities.findById(caller.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void activating_oneself_changes_nothing() throws Exception {
      MockHttpServletResponse response = patch(caller.id(), "{\"status\":\"ACTIVE\"}");

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(json(response).get("status").asString()).isEqualTo("ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "{\"status\":\"SUSPENDED\"}",
          "{\"status\":\"disabled\"}",
          "{\"status\":1}",
          "{}",
          "[]",
          "not json",
          ""
        })
    void a_bad_body_is_a_bad_request(String body) throws Exception {
      MockHttpServletResponse response = patch(other.id(), body);

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-admin-request");
      assertThat(identities.findById(other.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void inactive_is_a_bad_request() throws Exception {
      MockHttpServletResponse response = patch(other.id(), "{\"status\":\"INACTIVE\"}");

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader(HEADER)).isEqualTo("invalid-admin-request");
      assertThat(identities.findById(other.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void a_body_too_large_is_a_bad_request() throws Exception {
      String body = "{\"status\":\"ACTIVE\",\"pad\":\"" + "x".repeat(9000) + "\"}";

      assertThat(patch(other.id(), body).getStatus()).isEqualTo(400);
    }

    @Test
    void an_unknown_identity_is_not_found() throws Exception {
      MockHttpServletResponse response = patch(UUID.randomUUID(), "{\"status\":\"DISABLED\"}");

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getHeader(HEADER)).isEqualTo("identity-not-found");
    }
  }

  @Nested
  class Roles {

    private Identity other;

    @BeforeEach
    void signedIn() {
      signIn(READ, MANAGE, TASK_READ, TASK_WRITE);
      other = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
    }

    private String rolePath(Object id, String role) {
      return "/who/identities/" + id + "/roles/" + role;
    }

    @Test
    void assigns_and_removes_roles_idempotently() throws Exception {
      assertThat(send("PUT", rolePath(other.id(), "admin"), null).getStatus()).isEqualTo(204);
      assertThat(send("PUT", rolePath(other.id(), "admin"), null).getStatus()).isEqualTo(204);
      assertThat(held.get(other.id())).containsExactly(admin);

      assertThat(send("DELETE", rolePath(other.id(), "admin"), null).getStatus()).isEqualTo(204);
      assertThat(send("DELETE", rolePath(other.id(), "admin"), null).getStatus()).isEqualTo(204);
      assertThat(held.get(other.id())).isEmpty();
      verify(rbac, times(2)).assignRoleToIdentity(other, admin);
    }

    @Test
    void unknown_role_is_not_found() throws Exception {
      when(rbac.findRole("ghost")).thenReturn(Optional.empty());

      for (String method : List.of("PUT", "DELETE")) {
        MockHttpServletResponse response = send(method, rolePath(other.id(), "ghost"), null);
        assertThat(response.getStatus()).as(method).isEqualTo(404);
        assertThat(response.getHeader(HEADER)).isEqualTo("unknown-role");
      }
      verify(rbac, never()).assignRoleToIdentity(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-000000000000", "not-a-uuid"})
    void an_unknown_identity_is_not_found(String id) throws Exception {
      for (String method : List.of("PUT", "DELETE")) {
        MockHttpServletResponse response = send(method, rolePath(id, "admin"), null);
        assertThat(response.getStatus()).as(method).isEqualTo(404);
        assertThat(response.getHeader(HEADER)).isEqualTo("identity-not-found");
      }
    }

    @Test
    void a_removal_raced_by_another_is_still_done() throws Exception {
      doThrow(new IllegalArgumentException("not assigned"))
          .when(rbac)
          .removeRoleFromIdentity(other, admin);

      assertThat(send("DELETE", rolePath(other.id(), "admin"), null).getStatus()).isEqualTo(204);
    }

    @Test
    void lists_the_roles_with_their_permissions() throws Exception {
      MockHttpServletResponse response = get("/who/roles");

      assertThat(response.getStatus()).isEqualTo(200);
      JsonNode roles = json(response);
      assertThat(roles.valueStream().map(r -> r.get("name").asString()))
          .containsExactly("admin", "editor");
      assertThat(roles.get(0).get("permissions").valueStream().map(JsonNode::asString))
          .containsExactly("task.read", "task.write");
    }
  }

  @Nested
  class Role_grants {

    private Identity other;

    @BeforeEach
    void another() {
      other = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
    }

    private MockHttpServletResponse grant(String role) throws Exception {
      return send("PUT", "/who/identities/" + other.id() + "/roles/" + role, null);
    }

    @Test
    void a_role_beyond_the_callers_permissions_is_forbidden() throws Exception {
      signIn(MANAGE, TASK_READ);

      MockHttpServletResponse response = grant("editor");

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader(HEADER)).isEqualTo("role-grant-forbidden");
      assertThat(held.get(other.id())).isNull();
      verify(rbac, never()).assignRoleToIdentity(any(), any());
    }

    @Test
    void a_role_within_the_callers_permissions_is_granted() throws Exception {
      signIn(MANAGE, TASK_READ, TASK_WRITE);

      assertThat(grant("editor").getStatus()).isEqualTo(204);
      assertThat(held.get(other.id())).containsExactly(editor);
    }

    @Test
    void an_api_key_grants_only_within_its_scopes() throws Exception {
      // the key's owner holds every permission; the key is scoped to manage alone
      granted.addAll(Set.of(READ, MANAGE, TASK_READ, TASK_WRITE));
      signIn(SignInMethod.API_KEY, MANAGE);

      MockHttpServletResponse response = grant("editor");

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader(HEADER)).isEqualTo("role-grant-forbidden");
      verify(rbac, never()).assignRoleToIdentity(any(), any());
    }

    @Test
    void a_role_with_no_permissions_may_be_granted() throws Exception {
      Role member = new Role(UUID.randomUUID(), "member");
      when(rbac.findRole("member")).thenReturn(Optional.of(member));
      when(rbac.findPermissions(member)).thenReturn(Set.of());
      signIn(MANAGE);

      assertThat(grant("member").getStatus()).isEqualTo(204);
      assertThat(held.get(other.id())).containsExactly(member);
    }

    @Test
    void an_unknown_role_is_still_not_found() throws Exception {
      when(rbac.findRole("ghost")).thenReturn(Optional.empty());
      signIn(MANAGE);

      assertThat(grant("ghost").getStatus()).isEqualTo(404);
    }

    @Test
    void removing_a_role_is_not_restricted() throws Exception {
      held.computeIfAbsent(other.id(), id -> new HashSet<>()).add(editor);
      signIn(MANAGE);

      MockHttpServletResponse response =
          send("DELETE", "/who/identities/" + other.id() + "/roles/editor", null);

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(held.get(other.id())).isEmpty();
    }
  }

  @Nested
  class Recent_sign_in {

    private Identity other;

    @BeforeEach
    void another() {
      other = identity(IdentityStatus.ACTIVE, T0.plusSeconds(1));
    }

    private void staleBrowser() {
      browserSignIn(NOW.minus(RECENT).minusSeconds(1), READ, MANAGE);
    }

    @Test
    void a_stale_browser_must_sign_in_again_to_change_a_status() throws Exception {
      staleBrowser();

      MockHttpServletResponse response =
          send("PATCH", "/who/identities/" + other.id(), "{\"status\":\"DISABLED\"}");

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(HEADER)).isEqualTo("reauthentication-required");
      assertThat(identities.findById(other.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void a_stale_browser_must_sign_in_again_to_change_roles() throws Exception {
      staleBrowser();

      for (String method : List.of("PUT", "DELETE")) {
        MockHttpServletResponse response =
            send(method, "/who/identities/" + other.id() + "/roles/admin", null);
        assertThat(response.getStatus()).as(method).isEqualTo(401);
        assertThat(response.getHeader(HEADER)).isEqualTo("reauthentication-required");
      }
      verify(rbac, never()).assignRoleToIdentity(any(), any());
      verify(rbac, never()).removeRoleFromIdentity(any(), any());
    }

    @Test
    void a_stale_browser_is_told_so_before_any_lookup() throws Exception {
      staleBrowser();

      assertThat(send("PATCH", "/who/identities/not-a-uuid", "nope").getStatus()).isEqualTo(401);
    }

    @Test
    void a_stale_browser_without_the_permission_is_forbidden() throws Exception {
      browserSignIn(NOW.minus(RECENT).minusSeconds(1), READ);

      assertThat(send("PATCH", "/who/identities/" + other.id(), "{\"status\":\"DISABLED\"}"))
          .extracting(MockHttpServletResponse::getStatus)
          .isEqualTo(403);
    }

    @Test
    void a_stale_browser_may_still_read() throws Exception {
      staleBrowser();

      assertThat(get("/who/identities").getStatus()).isEqualTo(200);
      assertThat(get("/who/identities/" + other.id()).getStatus()).isEqualTo(200);
      assertThat(get("/who/roles").getStatus()).isEqualTo(200);
    }

    @Test
    void a_fresh_browser_may_change_an_identity() throws Exception {
      browserSignIn(NOW.minusSeconds(60), READ, MANAGE, TASK_READ, TASK_WRITE);

      assertThat(send("PATCH", "/who/identities/" + other.id(), "{\"status\":\"DISABLED\"}"))
          .extracting(MockHttpServletResponse::getStatus)
          .isEqualTo(200);
      assertThat(send("PUT", "/who/identities/" + other.id() + "/roles/admin", null).getStatus())
          .isEqualTo(204);
    }

    @Test
    void an_api_key_needs_no_recent_sign_in() throws Exception {
      signIn(SignInMethod.API_KEY, MANAGE, TASK_READ, TASK_WRITE);

      assertThat(send("PATCH", "/who/identities/" + other.id(), "{\"status\":\"DISABLED\"}"))
          .extracting(MockHttpServletResponse::getStatus)
          .isEqualTo(200);
      assertThat(send("PUT", "/who/identities/" + other.id() + "/roles/admin", null).getStatus())
          .isEqualTo(204);
    }
  }

  @Nested
  class Other_requests {

    @Test
    void pass_through() throws Exception {
      signIn(READ, MANAGE);

      for (String[] request :
          List.of(
              new String[] {"POST", "/who/identities"},
              new String[] {"DELETE", "/who/identities/" + caller.id()},
              new String[] {"GET", "/who/identities/" + caller.id() + "/roles"},
              new String[] {"POST", "/who/roles"},
              new String[] {"GET", "/who/me"},
              new String[] {"GET", "/identities"})) {
        reachedChain.set(false);
        send(request[0], request[1], null);
        assertThat(reachedChain).as(String.join(" ", request)).isTrue();
      }
    }
  }
}
