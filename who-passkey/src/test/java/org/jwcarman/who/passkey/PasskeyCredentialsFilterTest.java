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
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
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
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeyCredentialsFilterTest extends AbstractPasskeyTest {

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
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);
  private WhoSessionSecurityContextRepository sessionRepository;
  private PasskeyCredentialsFilter filter;
  private Identity alice;
  private Identity bob;

  @BeforeEach
  void setUp() {
    sessionRepository =
        new WhoSessionSecurityContextRepository(
            new WhoService(identityRepository, credentialIdentityRepository, i -> Set.of()),
            InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
            clock,
            WhoEventPublisher.NOOP);
    filter =
        new PasskeyCredentialsFilter(
            WhoPaths.defaults(),
            sessionRepository,
            passkeys,
            new LastCredentialGuard(
                identityRepository,
                List.of(new PasskeySignInCredentialCounter(jdbcClient)),
                new TransactionTemplate(transactionManager)),
            new WhoExceptionHandoff(null),
            json,
            clock,
            RECENT);
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
    ImmutableCredentialRecord record =
        CredentialRecords.registration(user.getId(), label)
            .created(created)
            .lastUsed(created)
            .build();
    CredentialRecords.register(credentialRepository, record);
    return passkeys.list(identity.id()).stream()
        .filter(s -> s.credentialId().equals(record.getCredentialId().toBase64UrlString()))
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
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    if (session != null) {
      request.setSession(session);
      SecurityContextHolder.setContext(sessionRepository.loadDeferredContext(request).get());
    }
    return filter(request);
  }

  private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
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
          .containsOnlyKeys("credentialId", "label", "created", "lastUsed")
          .containsEntry("label", "Laptop")
          .containsEntry("created", created.toString());
      assertThat(response.getContentAsString()).doesNotContain("\"id\"");
      assertThat(response.getContentAsString()).doesNotContain(older.id().toString());
      assertThat(reachedChain.get()).isFalse();
    }

    @Test
    void api_key_beside_cookie_is_forbidden() throws Exception {
      PasskeySummary passkey = passkey(alice, "Laptop", NOW.minusSeconds(60));
      HttpSession session = browser(alice, passkey.id(), NOW);
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/who/me/passkeys");
      request.setSession(session);
      SecurityContext apiKey = new SecurityContextImpl();
      apiKey.setAuthentication(
          new WhoAuthenticationToken(new WhoPrincipal(alice, Set.of()), UUID.randomUUID()));
      SecurityContextHolder.setContext(apiKey);

      assertThat(filter(request).getStatus()).isEqualTo(403);
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
      assertThat(passkeys.list(bob.id())).hasSize(2);
    }

    @Test
    void an_unknown_id_is_not_found_just_the_same() throws Exception {
      PasskeySummary mine = passkey(alice, "Laptop", NOW.minusSeconds(120));
      passkey(alice, "Phone", NOW.minusSeconds(60));
      HttpSession session = browser(alice, mine.id(), NOW);

      MockHttpServletResponse response = send("DELETE", "/who/me/passkeys/nope", session);

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void stale_sign_in_must_reauthenticate() throws Exception {
      PasskeySummary laptop = passkey(alice, "Laptop", NOW.minusSeconds(120));
      PasskeySummary phone = passkey(alice, "Phone", NOW.minusSeconds(60));
      HttpSession session = browser(alice, laptop.id(), NOW.minus(RECENT).minusSeconds(1));

      MockHttpServletResponse response =
          send("DELETE", "/who/me/passkeys/" + phone.credentialId(), session);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(WhoExceptionHandoff.HEADER))
          .isEqualTo("reauthentication-required");
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
      MockHttpServletRequest request =
          new MockHttpServletRequest("DELETE", "/who/me/passkeys/" + phone.credentialId());
      request.setSession(browser(alice, laptop.id(), NOW));
      SecurityContext apiKey = new SecurityContextImpl();
      apiKey.setAuthentication(
          new WhoAuthenticationToken(new WhoPrincipal(alice, Set.of()), UUID.randomUUID()));
      SecurityContextHolder.setContext(apiKey);

      assertThat(filter(request).getStatus()).isEqualTo(403);
      assertThat(passkeys.list(alice.id())).hasSize(2);
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("DELETE", "/who/me/passkeys/abc", null).getStatus()).isEqualTo(401);
    }
  }
}
