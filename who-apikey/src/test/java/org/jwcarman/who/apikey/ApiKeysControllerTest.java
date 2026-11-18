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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoErrorResolver;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeysControllerTest {

  // the same for an id that does not exist and one that is not the caller's
  private static final String NOT_FOUND =
      "{\"type\":\"urn:who:error:api-key-not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"No such API key\",\"code\":\"api-key-not-found\"}";

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);
  private static final String KEYS = "/who/me/api-keys";
  private static final String READ = "task.read";
  private static final String WRITE = "task.write";
  private static final JsonMapper JSON = new JsonMapper();

  /** A clock a test can move. */
  private static final class MutableClock extends Clock {

    private Instant now = NOW;

    void set(Instant instant) {
      now = instant;
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

  /** Credential-to-identity links, in memory. */
  private static final class Links implements CredentialIdentityRepository {

    private final Map<UUID, UUID> identityByCredential = new LinkedHashMap<>();

    @Override
    public Optional<UUID> findIdentityIdByCredentialId(UUID credentialId) {
      return Optional.ofNullable(identityByCredential.get(credentialId));
    }

    @Override
    public List<UUID> findCredentialIdsByIdentityId(UUID identityId) {
      return identityByCredential.entrySet().stream()
          .filter(entry -> entry.getValue().equals(identityId))
          .map(Map.Entry::getKey)
          .toList();
    }

    @Override
    public void link(UUID credentialId, UUID identityId) {
      identityByCredential.put(credentialId, identityId);
    }

    @Override
    public void unlink(UUID credentialId) {
      identityByCredential.remove(credentialId);
    }
  }

  /** API keys, in memory. */
  private static final class Keys implements ApiKeyCredentialRepository {

    private final Map<UUID, ApiKeyCredential> byId = new LinkedHashMap<>();

    @Override
    public Optional<ApiKeyCredential> findByKeyHash(String keyHash) {
      return byId.values().stream().filter(k -> k.keyHash().equals(keyHash)).findFirst();
    }

    @Override
    public Optional<ApiKeyCredential> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public ApiKeyCredential save(ApiKeyCredential credential) {
      byId.put(credential.id(), credential);
      return credential;
    }

    @Override
    public boolean deleteById(UUID id) {
      return byId.remove(id) != null;
    }

    @Override
    public boolean touch(UUID id, Instant now, Instant notSince) {
      ApiKeyCredential k = byId.get(id);
      byId.put(
          id,
          new ApiKeyCredential(
              k.id(), k.name(), k.keyHash(), k.scopes(), k.createdAt(), k.expiresAt(), now));
      return true;
    }

    @Override
    public boolean expireBy(UUID id, Instant expiresAt, Instant now) {
      ApiKeyCredential k = byId.get(id);
      if (k == null || k.expiredAt(now)) {
        return false;
      }
      Instant earlier = expiresAt.isBefore(k.expiresAt()) ? expiresAt : k.expiresAt();
      byId.put(
          id,
          new ApiKeyCredential(
              k.id(), k.name(), k.keyHash(), k.scopes(), k.createdAt(), earlier, k.lastUsedAt()));
      return true;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final Links links = new Links();
  private final Keys keys = new Keys();
  private final Identity alice = Identity.create(IdentityStatus.ACTIVE);
  private final Identity bob = Identity.create(IdentityStatus.ACTIVE);
  private ApiKeyService service;
  private WhoSessionSecurityContextRepository sessionRepository;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    IdentityRepository identities = mock(IdentityRepository.class);
    for (Identity identity : List.of(alice, bob)) {
      when(identities.findById(identity.id())).thenReturn(Optional.of(identity));
    }
    service =
        new ApiKeyService(
            keys,
            links,
            identity -> identity.equals(alice) ? Set.of(READ, WRITE) : Set.of(READ),
            new ApiKeyFormat(ApiKeyFormat.DEFAULT_PREFIX),
            Duration.ofDays(365),
            WhoEventPublisher.NOOP,
            clock);
    sessionRepository =
        new WhoSessionSecurityContextRepository(
            new WhoService(identities, links, i -> Set.of()),
            InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
            clock,
            WhoEventPublisher.NOOP,
            _ -> false);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ApiKeysController(service, sessionRepository, JSON, clock, RECENT))
            .setHandlerExceptionResolvers(new WhoErrorResolver(WhoPaths.defaults()))
            .build();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private NewApiKey key(Identity identity, String name) {
    return service.create(identity, name, Duration.ofDays(30), Set.of(READ));
  }

  /** A browser of {@code identity} that signed in at {@code at}. */
  private HttpSession browser(Identity identity, Instant at) {
    clock.set(at);
    MockHttpServletRequest request = new MockHttpServletRequest();
    // the session re-resolves its sign-in credential on every request: it must stay linked
    UUID emailCredential = UUID.randomUUID();
    links.link(emailCredential, identity.id());
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), emailCredential, SignInMethod.EMAIL_LINK));
    sessionRepository.saveContext(context, request, new MockHttpServletResponse());
    clock.set(NOW);
    return request.getSession(false);
  }

  private HttpSession recentBrowser(Identity identity) {
    return browser(identity, NOW.minusSeconds(60));
  }

  private HttpSession staleBrowser(Identity identity) {
    return browser(identity, NOW.minus(RECENT).minusSeconds(1));
  }

  private MockHttpServletRequest request(String method, String uri, String body) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    if (body != null) {
      request.setContentType("application/json");
      request.setContent(body.getBytes(StandardCharsets.UTF_8));
    }
    return request;
  }

  /** Sends a request from {@code session}, authenticated as the session loads. */
  private MockHttpServletResponse send(String method, String uri, String body, HttpSession session)
      throws Exception {
    MockHttpServletRequest request = request(method, uri, body);
    if (session != null) {
      request.setSession(session);
      SecurityContextHolder.setContext(sessionRepository.loadDeferredContext(request).get());
    }
    return filter(request);
  }

  /** Sends a request authenticated by a bearer token, or an API key, of {@code identity}. */
  private MockHttpServletResponse sendAs(
      SignInMethod method,
      UUID credentialId,
      Identity identity,
      String verb,
      String uri,
      String body)
      throws Exception {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of()), credentialId, method));
    SecurityContextHolder.setContext(context);
    return filter(request(verb, uri, body));
  }

  private MockHttpServletResponse bearer(Identity identity, String verb, String uri, String body)
      throws Exception {
    return sendAs(SignInMethod.JWT, UUID.randomUUID(), identity, verb, uri, body);
  }

  /** Dispatches the request as built, with the security context already set for it. */
  private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
    return mvc.perform(servletContext -> request).andReturn().getResponse();
  }

  private static JsonNode json(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString());
  }

  private static String createBody(String name, String lifetime, String... scopes) {
    StringBuilder body = new StringBuilder("{\"name\":\"" + name + "\",\"lifetime\":\"");
    body.append(lifetime).append("\",\"scopes\":[");
    for (int i = 0; i < scopes.length; i++) {
      body.append(i == 0 ? "" : ",").append('"').append(scopes[i]).append('"');
    }
    return body.append("]}").toString();
  }

  private boolean signsIn(String rawKey) {
    return keys.findByKeyHash(ApiKeyService.sha256Hex(rawKey))
        .filter(k -> !k.expiredAt(clock.instant()))
        .isPresent();
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_keys_without_their_secrets() throws Exception {
      NewApiKey ci = key(alice, "CI");
      key(bob, "Bob's");

      MockHttpServletResponse response = send("GET", KEYS, null, recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith("application/json");
      JsonNode body = json(response);
      assertThat(body.size()).isEqualTo(1);
      JsonNode item = body.get(0);
      assertThat(item.properties())
          .extracting(Map.Entry::getKey)
          .containsExactly("id", "name", "scopes", "createdAt", "expiresAt", "lastUsedAt");
      assertThat(item.get("id").asString()).isEqualTo(ci.id().toString());
      assertThat(item.get("name").asString()).isEqualTo("CI");
      assertThat(item.get("scopes").get(0).asString()).isEqualTo(READ);
      assertThat(item.get("createdAt").asString()).isEqualTo(NOW.toString());
      assertThat(item.get("expiresAt").asString()).isEqualTo(ci.expiresAt().toString());
      assertThat(item.get("lastUsedAt").isNull()).isTrue();
      assertThat(response.getContentAsString()).doesNotContain(ci.key());
    }

    @Test
    void shows_when_a_key_was_last_used() throws Exception {
      NewApiKey ci = key(alice, "CI");
      keys.touch(ci.id(), NOW.plusSeconds(5), NOW);

      MockHttpServletResponse response = send("GET", KEYS, null, staleBrowser(alice));

      assertThat(json(response).get(0).get("lastUsedAt").asString())
          .isEqualTo(NOW.plusSeconds(5).toString());
    }

    @Test
    void a_stale_browser_may_list() throws Exception {
      key(alice, "CI");

      assertThat(send("GET", KEYS, null, staleBrowser(alice)).getStatus()).isEqualTo(200);
    }

    @Test
    void a_bearer_token_may_list() throws Exception {
      key(alice, "CI");

      MockHttpServletResponse response = bearer(alice, "GET", KEYS, null);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(json(response).size()).isEqualTo(1);
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("GET", KEYS, null, null).getStatus()).isEqualTo(401);
    }
  }

  @Nested
  class Creating {

    @Test
    void creates_a_key_and_shows_it_once() throws Exception {
      MockHttpServletResponse response =
          send("POST", KEYS, createBody("CI", "P30D", READ), recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(response.getContentType()).startsWith("application/json");
      JsonNode body = json(response);
      assertThat(body.properties())
          .extracting(Map.Entry::getKey)
          .containsExactly("id", "name", "scopes", "expiresAt", "key");
      assertThat(body.get("name").asString()).isEqualTo("CI");
      assertThat(body.get("scopes").get(0).asString()).isEqualTo(READ);
      assertThat(body.get("expiresAt").asString())
          .isEqualTo(NOW.plus(Duration.ofDays(30)).toString());
      String rawKey = body.get("key").asString();
      assertThat(signsIn(rawKey)).isTrue();
      assertThat(service.findByIdentity(alice.id()))
          .singleElement()
          .satisfies(k -> assertThat(k.id()).hasToString(body.get("id").asString()));
    }

    @Test
    void raw_key_appears_only_in_the_create_response() throws Exception {
      Logger who = (Logger) LoggerFactory.getLogger("org.jwcarman.who");
      Level before = who.getLevel();
      ListAppender<ILoggingEvent> logs = new ListAppender<>();
      logs.start();
      who.setLevel(Level.DEBUG);
      who.addAppender(logs);
      String rawKey;
      String listed;
      try {
        HttpSession session = recentBrowser(alice);
        rawKey =
            json(send("POST", KEYS, createBody("CI", "P30D", READ), session)).get("key").asString();
        listed = send("GET", KEYS, null, session).getContentAsString();
      } finally {
        who.detachAppender(logs);
        who.setLevel(before);
      }

      assertThat(listed).contains("CI").doesNotContain(rawKey);
      assertThat(logs.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .noneMatch(line -> line.contains(rawKey));
    }

    @Test
    void a_bearer_token_needs_no_recent_sign_in() throws Exception {
      MockHttpServletResponse response = bearer(alice, "POST", KEYS, createBody("CI", "P1D", READ));

      assertThat(response.getStatus()).isEqualTo(201);
      assertThat(signsIn(json(response).get("key").asString())).isTrue();
    }

    @Test
    void a_stale_browser_must_reauthenticate_and_makes_no_key() throws Exception {
      MockHttpServletResponse response =
          send("POST", KEYS, createBody("CI", "P1D", READ), staleBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader("Who-Error")).isEqualTo("reauthentication-required");
      assertThat(service.findByIdentity(alice.id())).isEmpty();
    }

    @Test
    void scope_the_owner_lacks_is_bad_request() throws Exception {
      MockHttpServletResponse response =
          send("POST", KEYS, createBody("CI", "P1D", READ, "task.delete"), recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader("Who-Error")).isEqualTo("invalid-request");
      assertThat(service.findByIdentity(alice.id())).isEmpty();
    }

    @Test
    void an_unknown_scope_is_bad_request() throws Exception {
      assertThat(
              send("POST", KEYS, createBody("CI", "P1D", "no.such"), recentBrowser(alice))
                  .getStatus())
          .isEqualTo(400);
    }

    @Test
    void a_blank_name_is_bad_request() throws Exception {
      assertThat(send("POST", KEYS, createBody(" ", "P1D", READ), recentBrowser(alice)).getStatus())
          .isEqualTo(400);
    }

    @Test
    void a_lifetime_that_is_not_iso_8601_is_bad_request() throws Exception {
      assertThat(
              send("POST", KEYS, createBody("CI", "30 days", READ), recentBrowser(alice))
                  .getStatus())
          .isEqualTo(400);
    }

    @Test
    void a_lifetime_over_the_maximum_is_bad_request() throws Exception {
      assertThat(
              send("POST", KEYS, createBody("CI", "P366D", READ), recentBrowser(alice)).getStatus())
          .isEqualTo(400);
    }

    @Test
    void a_body_that_is_not_json_is_bad_request() throws Exception {
      MockHttpServletResponse response = send("POST", KEYS, "not json", recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader("Who-Error")).isEqualTo("invalid-request");
    }

    @Test
    void a_body_over_8192_bytes_is_refused() throws Exception {
      String name = "x".repeat(8192);

      MockHttpServletResponse response =
          send("POST", KEYS, createBody(name, "P1D", READ), recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getHeader("Who-Error")).isEqualTo("invalid-request");
      assertThat(service.findByIdentity(alice.id())).isEmpty();
    }

    @Test
    void the_answers_are_the_records() throws NoSuchMethodException {
      // Spring registers a handler's return type for native images; a void handler hides it
      assertThat(
              ApiKeysController.class
                  .getMethod("list", HttpServletRequest.class)
                  .getGenericReturnType()
                  .getTypeName())
          .isEqualTo("java.util.List<" + ApiKeyView.class.getName() + ">");
      assertThat(
              ApiKeysController.class.getMethod("create", HttpServletRequest.class).getReturnType())
          .isEqualTo(CreatedApiKey.class);
    }

    @Test
    void a_body_missing_its_scopes_is_bad_request() throws Exception {
      assertThat(
              send("POST", KEYS, "{\"name\":\"CI\",\"lifetime\":\"P1D\"}", recentBrowser(alice))
                  .getStatus())
          .isEqualTo(400);
    }

    @Test
    void a_scope_that_is_not_a_string_is_bad_request() throws Exception {
      assertThat(
              send(
                      "POST",
                      KEYS,
                      "{\"name\":\"CI\",\"lifetime\":\"P1D\",\"scopes\":[1]}",
                      recentBrowser(alice))
                  .getStatus())
          .isEqualTo(400);
    }
  }

  @Nested
  class Rotating {

    @Test
    void rotation_returns_a_new_key() throws Exception {
      NewApiKey old = key(alice, "CI");

      MockHttpServletResponse response =
          send(
              "POST",
              KEYS + "/" + old.id() + "/rotations",
              "{\"overlap\":\"PT1H\"}",
              recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(201);
      JsonNode body = json(response);
      assertThat(body.properties())
          .extracting(Map.Entry::getKey)
          .containsExactly("id", "name", "scopes", "expiresAt", "key");
      assertThat(body.get("id").asString()).isNotEqualTo(old.id().toString());
      assertThat(body.get("name").asString()).isEqualTo("CI");
      String rawKey = body.get("key").asString();
      assertThat(rawKey).isNotEqualTo(old.key());
      assertThat(signsIn(rawKey)).isTrue();
      assertThat(keys.findById(old.id()).orElseThrow().expiresAt())
          .isEqualTo(NOW.plus(Duration.ofHours(1)));
      assertThat(send("GET", KEYS, null, recentBrowser(alice)).getContentAsString())
          .doesNotContain(rawKey);
    }

    @Test
    void a_bearer_token_may_rotate() throws Exception {
      NewApiKey old = key(alice, "CI");

      assertThat(
              bearer(alice, "POST", KEYS + "/" + old.id() + "/rotations", "{\"overlap\":\"PT0S\"}")
                  .getStatus())
          .isEqualTo(201);
    }

    @Test
    void another_identitys_id_is_not_found() throws Exception {
      NewApiKey bobs = key(bob, "Bob's");

      MockHttpServletResponse response =
          send(
              "POST",
              KEYS + "/" + bobs.id() + "/rotations",
              "{\"overlap\":\"PT0S\"}",
              recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getContentAsString()).isEqualTo(NOT_FOUND);
      assertThat(signsIn(bobs.key())).isTrue();
      assertThat(service.findByIdentity(bob.id())).hasSize(1);
    }

    @Test
    void an_unknown_id_is_not_found_just_the_same() throws Exception {
      for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
        MockHttpServletResponse response =
            send(
                "POST",
                KEYS + "/" + id + "/rotations",
                "{\"overlap\":\"PT0S\"}",
                recentBrowser(alice));
        assertThat(response.getStatus()).as(id).isEqualTo(404);
        assertThat(response.getHeader("Who-Error")).isEqualTo("api-key-not-found");
        assertThat(response.getContentAsString()).isEqualTo(NOT_FOUND);
      }
    }

    @Test
    void a_stale_browser_must_reauthenticate_before_the_id_is_looked_at() throws Exception {
      NewApiKey bobs = key(bob, "Bob's");

      MockHttpServletResponse response =
          send(
              "POST",
              KEYS + "/" + bobs.id() + "/rotations",
              "{\"overlap\":\"PT0S\"}",
              staleBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void an_overlap_that_is_not_iso_8601_is_bad_request() throws Exception {
      NewApiKey old = key(alice, "CI");

      MockHttpServletResponse response =
          send(
              "POST",
              KEYS + "/" + old.id() + "/rotations",
              "{\"overlap\":\"an hour\"}",
              recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(signsIn(old.key())).isTrue();
    }

    @Test
    void a_missing_overlap_is_bad_request() throws Exception {
      NewApiKey old = key(alice, "CI");

      assertThat(
              send("POST", KEYS + "/" + old.id() + "/rotations", "{}", recentBrowser(alice))
                  .getStatus())
          .isEqualTo(400);
    }

    @Test
    void an_overlap_over_the_maximum_is_bad_request() throws Exception {
      NewApiKey old = key(alice, "CI");

      assertThat(
              send(
                      "POST",
                      KEYS + "/" + old.id() + "/rotations",
                      "{\"overlap\":\"P8D\"}",
                      recentBrowser(alice))
                  .getStatus())
          .isEqualTo(400);
    }
  }

  @Nested
  class Revoking {

    @Test
    void revokes_the_callers_key() throws Exception {
      NewApiKey ci = key(alice, "CI");

      MockHttpServletResponse response =
          send("DELETE", KEYS + "/" + ci.id(), null, staleBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(signsIn(ci.key())).isFalse();
    }

    @Test
    void a_bearer_token_may_revoke() throws Exception {
      NewApiKey ci = key(alice, "CI");

      assertThat(bearer(alice, "DELETE", KEYS + "/" + ci.id(), null).getStatus()).isEqualTo(204);
      assertThat(signsIn(ci.key())).isFalse();
    }

    @Test
    void another_identitys_id_is_not_found() throws Exception {
      NewApiKey bobs = key(bob, "Bob's");

      MockHttpServletResponse response =
          send("DELETE", KEYS + "/" + bobs.id(), null, recentBrowser(alice));

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getContentAsString()).isEqualTo(NOT_FOUND);
      assertThat(signsIn(bobs.key())).isTrue();
    }

    @Test
    void an_unknown_id_is_not_found_just_the_same() throws Exception {
      for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
        MockHttpServletResponse response =
            send("DELETE", KEYS + "/" + id, null, recentBrowser(alice));
        assertThat(response.getStatus()).as(id).isEqualTo(404);
        assertThat(response.getHeader("Who-Error")).isEqualTo("api-key-not-found");
        assertThat(response.getContentAsString()).isEqualTo(NOT_FOUND);
      }
    }
  }

  @Nested
  class Api_keys {

    @Test
    void an_api_key_cannot_manage_keys() throws Exception {
      NewApiKey own = key(alice, "Own");
      NewApiKey other = key(alice, "Other");
      List<String[]> requests = new ArrayList<>();
      requests.add(new String[] {"GET", KEYS, null});
      requests.add(new String[] {"POST", KEYS, createBody("Minted", "P1D", READ)});
      requests.add(
          new String[] {"POST", KEYS + "/" + other.id() + "/rotations", "{\"overlap\":\"PT0S\"}"});
      requests.add(new String[] {"DELETE", KEYS + "/" + other.id(), null});

      for (String[] r : requests) {
        MockHttpServletResponse response =
            sendAs(SignInMethod.API_KEY, own.id(), alice, r[0], r[1], r[2]);
        assertThat(response.getStatus()).as(r[0] + " " + r[1]).isEqualTo(403);
        assertThat(response.getHeader("Who-Error"))
            .as(r[0] + " " + r[1])
            .isEqualTo("api-key-cannot-manage-keys");
      }
      assertThat(service.findByIdentity(alice.id())).hasSize(2);
      assertThat(signsIn(other.key())).isTrue();
    }

    @Test
    void an_api_key_beside_a_session_cookie_cannot_manage_keys() throws Exception {
      NewApiKey own = key(alice, "Own");
      MockHttpServletRequest request = request("GET", KEYS, null);
      request.setSession(recentBrowser(alice));
      SecurityContext context = new SecurityContextImpl();
      context.setAuthentication(
          new WhoAuthenticationToken(
              new WhoPrincipal(alice, Set.of()), own.id(), SignInMethod.API_KEY));
      SecurityContextHolder.setContext(context);

      MockHttpServletResponse response = filter(request);

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Who-Error")).isEqualTo("api-key-cannot-manage-keys");
    }

    @Test
    void current_answers_the_key_in_use() throws Exception {
      NewApiKey own = key(alice, "Own");
      key(alice, "Other");

      MockHttpServletResponse response =
          sendAs(SignInMethod.API_KEY, own.id(), alice, "GET", KEYS + "/current", null);

      assertThat(response.getStatus()).isEqualTo(200);
      JsonNode body = json(response);
      assertThat(body.properties())
          .extracting(Map.Entry::getKey)
          .containsExactly("id", "name", "scopes", "createdAt", "expiresAt", "lastUsedAt");
      assertThat(body.get("id").asString()).isEqualTo(own.id().toString());
      assertThat(body.get("name").asString()).isEqualTo("Own");
      assertThat(response.getContentAsString()).doesNotContain(own.key());
    }

    @Test
    void current_without_a_key_is_not_found() throws Exception {
      MockHttpServletResponse fromBrowser =
          send("GET", KEYS + "/current", null, recentBrowser(alice));
      MockHttpServletResponse fromBearer = bearer(alice, "GET", KEYS + "/current", null);

      assertThat(fromBrowser.getStatus()).isEqualTo(404);
      assertThat(fromBrowser.getContentAsString()).isEqualTo(NOT_FOUND);
      assertThat(fromBearer.getStatus()).isEqualTo(404);
    }

    @Test
    void current_signed_out_is_unauthorized() throws Exception {
      assertThat(send("GET", KEYS + "/current", null, null).getStatus()).isEqualTo(401);
    }
  }

  @Test
  void the_created_answer_never_shows_the_key_in_to_string() {
    NewApiKey created = key(alice, "CI");

    assertThat(CreatedApiKey.of(created).toString()).contains("CI").doesNotContain(created.key());
  }
}
