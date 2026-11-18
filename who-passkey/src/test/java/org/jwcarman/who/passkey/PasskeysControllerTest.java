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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoErrorResolver;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeysControllerTest extends AbstractPasskeyTest {

  // the same for an id that does not exist and one that is not the caller's
  private static final String NOT_FOUND =
      "{\"type\":\"urn:who:error:passkey-not-found\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"No such passkey\",\"code\":\"passkey-not-found\"}";

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);

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

  @Autowired private PasskeyService passkeys;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private JdbcWhoPasskeyUserRepository userRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private PlatformTransactionManager transactionManager;

  private final MutableClock clock = new MutableClock();
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<UUID, PublicKeyCredentialUserEntity> users = new HashMap<>();
  private WhoSessionSecurityContextRepository sessionRepository;
  private MockMvc mvc;
  private Identity alice;
  private Identity bob;

  @BeforeEach
  void setUp() {
    sessionRepository =
        new WhoSessionSecurityContextRepository(
            new WhoService(identityRepository, credentialIdentityRepository, i -> Set.of()),
            InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
            clock,
            WhoEventPublisher.NOOP,
            _ -> false);
    PasskeysController controller =
        new PasskeysController(
            sessionRepository,
            passkeys,
            new LastCredentialGuard(
                identityRepository,
                List.of(new PasskeySignInCredentialCounter(jdbcClient)),
                transactionManager),
            clock,
            RECENT);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setHandlerExceptionResolvers(new WhoErrorResolver(WhoPaths.defaults()))
            .build();
    alice = identityRepository.save(Identity.create());
    bob = identityRepository.save(Identity.create());
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  /** Registers a passkey for the identity and returns its summary. */
  private PasskeySummary passkey(Identity identity, String label, Instant created) {
    PublicKeyCredentialUserEntity user =
        users.computeIfAbsent(identity.id(), id -> userRepository.create(id, id.toString()));
    ImmutableCredentialRecord stored =
        CredentialRecords.registration(user.getId(), label)
            .created(created)
            .lastUsed(created)
            .build();
    CredentialRecords.register(credentialRepository, stored);
    return passkeys.list(identity.id()).stream()
        .filter(s -> s.credentialId().equals(stored.getCredentialId().toBase64UrlString()))
        .findFirst()
        .orElseThrow();
  }

  /** A browser of {@code identity} that signed in at {@code at}, with the passkey as its factor. */
  private HttpSession browser(Identity identity, UUID credentialUuid, Instant at) {
    clock.set(at);
    MockHttpServletRequest request = new MockHttpServletRequest();
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), credentialUuid, SignInMethod.PASSKEY));
    sessionRepository.saveContext(context, request, new MockHttpServletResponse());
    clock.set(NOW);
    return request.getSession(false);
  }

  /** Sends a request from {@code session}, authenticated as the session loads. */
  private MockHttpServletResponse send(String method, String uri, HttpSession session)
      throws Exception {
    MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), uri);
    if (session != null) {
      // loaded on the request itself, as the chain's SecurityContextHolderFilter does
      request
          .session((MockHttpSession) session)
          .with(
              loading -> {
                SecurityContextHolder.setContext(
                    sessionRepository.loadDeferredContext(loading).get());
                return loading;
              });
    }
    return perform(request);
  }

  private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn().getResponse();
  }

  /** Signs the request in with a stateless token beside the browser's cookie. */
  private static void signInStateless(Identity identity, SignInMethod method) {
    SecurityContext stateless = new SecurityContextImpl();
    stateless.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), UUID.randomUUID(), method));
    SecurityContextHolder.setContext(stateless);
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_passkeys_oldest_first() throws Exception {
      Instant created = NOW.minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
      PasskeySummary newer = passkey(alice, "Phone", created.plusSeconds(3600));
      PasskeySummary older = passkey(alice, "Laptop", created);
      passkey(bob, "Bob's", created);
      HttpSession session = browser(alice, older.id(), NOW);

      MockHttpServletResponse response = send("GET", "/who/me/passkeys", session);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith("application/json");
      List<Map<String, Object>> body =
          json.readValue(
              response.getContentAsString(),
              json.getTypeFactory().constructCollectionType(List.class, Map.class));
      assertThat(body)
          .extracting(m -> m.get("credentialId"))
          .containsExactly(older.credentialId(), newer.credentialId());
      assertThat(body.getFirst())
          .containsOnlyKeys("credentialId", "label", "createdAt", "lastUsedAt")
          .containsEntry("label", "Laptop")
          .containsEntry("createdAt", created.toString());
      assertThat(response.getContentAsString()).doesNotContain("\"id\"");
      assertThat(response.getContentAsString()).doesNotContain(older.id().toString());
    }

    @Test
    void api_key_beside_cookie_is_forbidden() throws Exception {
      PasskeySummary passkey = passkey(alice, "Laptop", NOW.minusSeconds(60));
      HttpSession session = browser(alice, passkey.id(), NOW);
      signInStateless(alice, SignInMethod.API_KEY);

      MockHttpServletResponse response =
          perform(get("/who/me/passkeys").session((MockHttpSession) session));

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
    }

    @Test
    void a_bearer_token_is_refused() throws Exception {
      signInStateless(alice, SignInMethod.JWT);

      MockHttpServletResponse response = perform(get("/who/me/passkeys"));

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
    }

    @Test
    void the_answer_is_the_list_of_records() throws NoSuchMethodException {
      // Spring registers a handler's return type for native images; a void handler hides it
      assertThat(
              PasskeysController.class
                  .getMethod("list", HttpServletRequest.class)
                  .getGenericReturnType()
                  .getTypeName())
          .isEqualTo("java.util.List<" + PasskeyView.class.getName() + ">");
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("GET", "/who/me/passkeys", null).getStatus()).isEqualTo(401);
    }
  }

  @Nested
  class Removing {

    @Test
    void removes_a_passkey() throws Exception {
      PasskeySummary laptop = passkey(alice, "Laptop", NOW.minusSeconds(120));
      PasskeySummary phone = passkey(alice, "Phone", NOW.minusSeconds(60));
      HttpSession session = browser(alice, laptop.id(), NOW);

      MockHttpServletResponse response =
          send("DELETE", "/who/me/passkeys/" + phone.credentialId(), session);

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(passkeys.list(alice.id()))
          .extracting(PasskeySummary::id)
          .containsExactly(laptop.id());
    }

    @Test
    void another_identitys_id_is_not_found() throws Exception {
      PasskeySummary mine = passkey(alice, "Laptop", NOW.minusSeconds(120));
      passkey(alice, "Phone", NOW.minusSeconds(60));
      PasskeySummary theirs = passkey(bob, "Bob's", NOW.minusSeconds(60));
      passkey(bob, "Bob's other", NOW.minusSeconds(30));
      HttpSession session = browser(alice, mine.id(), NOW);

      MockHttpServletResponse response =
          send("DELETE", "/who/me/passkeys/" + theirs.credentialId(), session);

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getHeader("Who-Error")).isEqualTo("passkey-not-found");
      assertThat(passkeys.list(bob.id())).hasSize(2);
    }

    @Test
    void an_unknown_id_is_not_found_just_the_same() throws Exception {
      PasskeySummary mine = passkey(alice, "Laptop", NOW.minusSeconds(120));
      passkey(alice, "Phone", NOW.minusSeconds(60));
      HttpSession session = browser(alice, mine.id(), NOW);

      MockHttpServletResponse response = send("DELETE", "/who/me/passkeys/nope", session);

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getHeader("Who-Error")).isEqualTo("passkey-not-found");
      assertThat(response.getContentAsString()).isEqualTo(NOT_FOUND);
    }

    @Test
    void stale_sign_in_must_reauthenticate() throws Exception {
      PasskeySummary laptop = passkey(alice, "Laptop", NOW.minusSeconds(120));
      PasskeySummary phone = passkey(alice, "Phone", NOW.minusSeconds(60));
      HttpSession session = browser(alice, laptop.id(), NOW.minus(RECENT).minusSeconds(1));

      MockHttpServletResponse response =
          send("DELETE", "/who/me/passkeys/" + phone.credentialId(), session);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader("Who-Error")).isEqualTo("reauthentication-required");
      assertThat(passkeys.list(alice.id())).hasSize(2);
    }

    @Test
    void last_credential_is_conflict() throws Exception {
      PasskeySummary only = passkey(alice, "Laptop", NOW.minusSeconds(120));
      HttpSession session = browser(alice, only.id(), NOW);

      MockHttpServletResponse response =
          send("DELETE", "/who/me/passkeys/" + only.credentialId(), session);

      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(passkeys.list(alice.id())).hasSize(1);
    }

    @Test
    void api_key_beside_cookie_is_forbidden() throws Exception {
      PasskeySummary laptop = passkey(alice, "Laptop", NOW.minusSeconds(120));
      PasskeySummary phone = passkey(alice, "Phone", NOW.minusSeconds(60));
      HttpSession session = browser(alice, laptop.id(), NOW);
      signInStateless(alice, SignInMethod.API_KEY);

      MockHttpServletResponse response =
          perform(
              delete("/who/me/passkeys/" + phone.credentialId())
                  .session((MockHttpSession) session));

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
      assertThat(passkeys.list(alice.id())).hasSize(2);
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("DELETE", "/who/me/passkeys/abc", null).getStatus()).isEqualTo(401);
    }
  }
}
