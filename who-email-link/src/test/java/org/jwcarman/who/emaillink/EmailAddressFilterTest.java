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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailAddressFilterTest extends AbstractEmailLinkTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);

  private static final EmailLinkSettings SETTINGS =
      new EmailLinkSettings(
          "https://app.test/signin",
          Duration.ofMinutes(5),
          Duration.ofSeconds(60),
          6,
          3,
          "an-address-filter-test-secret-of-32+-chars");

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

  @Autowired private EmailCredentialService credentials;
  @Autowired private EmailAddressRequestRepository requests;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private PlatformTransactionManager transactionManager;

  private final MutableClock clock = new MutableClock();
  private final JsonMapper json = JsonMapper.builder().build();
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);
  private WhoSessionSecurityContextRepository sessionRepository;
  private EmailAddressFilter filter;
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
    filter =
        new EmailAddressFilter(
            WhoPaths.defaults(),
            new EmailAddressChanges(
                credentials,
                requests,
                email -> {},
                SETTINGS,
                "https://app.test/profile",
                clock,
                WhoEventPublisher.NOOP),
            credentials,
            new LastCredentialGuard(
                identityRepository,
                List.of(new EmailSignInCredentialCounter(jdbcClient)),
                transactionManager),
            sessionRepository,
            new WhoExceptionHandoff(null),
            Runnable::run,
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

  private EmailCredential address(Identity identity, String address) {
    return credentials.register(identity, address);
  }

  /** A browser of {@code identity} that signed in at {@code at}, with the address as its factor. */
  private HttpSession browser(Identity identity, UUID credentialId, Instant at) {
    clock.set(at);
    MockHttpServletRequest request = new MockHttpServletRequest();
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), credentialId, SignInMethod.EMAIL_LINK));
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

  private void authenticateWithApiKey(Identity identity) {
    SecurityContext apiKey = new SecurityContextImpl();
    apiKey.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), UUID.randomUUID(), SignInMethod.API_KEY));
    SecurityContextHolder.setContext(apiKey);
  }

  private List<Map<String, Object>> parse(MockHttpServletResponse response) throws Exception {
    return json.readValue(
        response.getContentAsString(),
        json.getTypeFactory().constructCollectionType(List.class, Map.class));
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_addresses_oldest_first() throws Exception {
      EmailCredential first = address(alice, "first@example.com");
      EmailCredential second = address(alice, "second@example.com");
      address(bob, "bob@example.com");
      HttpSession session = browser(alice, first.id(), NOW);

      MockHttpServletResponse response = send("GET", "/who/me/email-addresses", session);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith("application/json");
      List<Map<String, Object>> body = parse(response);
      assertThat(body)
          .extracting(m -> m.get("id"))
          .containsExactly(first.id().toString(), second.id().toString());
      assertThat(body.getFirst())
          .containsOnlyKeys("id", "address", "createdAt")
          .containsEntry("address", "first@example.com");
      assertThat(Instant.parse((String) body.getFirst().get("createdAt")))
          .isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
      assertThat(reachedChain.get()).isFalse();
    }

    @Test
    void an_api_key_alone_may_list() throws Exception {
      address(alice, "first@example.com");
      address(bob, "bob@example.com");
      authenticateWithApiKey(alice);

      MockHttpServletResponse response =
          filter(new MockHttpServletRequest("GET", "/who/me/email-addresses"));

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(parse(response))
          .extracting(m -> m.get("address"))
          .containsExactly("first@example.com");
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("GET", "/who/me/email-addresses", null).getStatus()).isEqualTo(401);
    }
  }

  @Nested
  class Removing {

    private static final String PATH = "/who/me/email-addresses/";

    @Test
    void removes_an_address() throws Exception {
      EmailCredential laptop = address(alice, "laptop@example.com");
      EmailCredential phone = address(alice, "phone@example.com");
      HttpSession session = browser(alice, laptop.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + phone.id(), session);

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(credentials.findById(phone.id())).isEmpty();
      assertThat(credentials.findById(laptop.id())).isPresent();
    }

    @Test
    void removing_the_address_the_session_signed_in_with_is_allowed() throws Exception {
      EmailCredential signedInWith = address(alice, "laptop@example.com");
      address(alice, "phone@example.com");
      HttpSession session = browser(alice, signedInWith.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + signedInWith.id(), session);

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(credentials.findById(signedInWith.id())).isEmpty();
      // who re-resolves the session's credential on every request, so a revoked credential
      // ends the session: the person signs in again with the address that is left
      assertThat(send("GET", "/who/me/email-addresses", session).getStatus()).isEqualTo(401);
    }

    @Test
    void removing_another_address_leaves_the_session_working() throws Exception {
      EmailCredential signedInWith = address(alice, "laptop@example.com");
      EmailCredential other = address(alice, "phone@example.com");
      HttpSession session = browser(alice, signedInWith.id(), NOW);

      assertThat(send("DELETE", PATH + other.id(), session).getStatus()).isEqualTo(204);

      assertThat(send("GET", "/who/me/email-addresses", session).getStatus()).isEqualTo(200);
    }

    @Test
    void another_identitys_id_is_not_found() throws Exception {
      EmailCredential mine = address(alice, "mine@example.com");
      address(alice, "mine-too@example.com");
      EmailCredential theirs = address(bob, "bob@example.com");
      address(bob, "bob-too@example.com");
      HttpSession session = browser(alice, mine.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + theirs.id(), session);

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getContentAsString()).isEmpty();
      assertThat(credentials.findById(theirs.id())).isPresent();
    }

    @Test
    void an_unknown_id_is_not_found_just_the_same() throws Exception {
      EmailCredential mine = address(alice, "mine@example.com");
      address(alice, "mine-too@example.com");
      HttpSession session = browser(alice, mine.id(), NOW);

      assertThat(send("DELETE", PATH + UUID.randomUUID(), session).getStatus()).isEqualTo(404);
      MockHttpServletResponse notAnId = send("DELETE", PATH + "nope", session);
      assertThat(notAnId.getStatus()).isEqualTo(404);
      assertThat(notAnId.getContentAsString()).isEmpty();
    }

    @Test
    void stale_sign_in_must_reauthenticate() throws Exception {
      EmailCredential laptop = address(alice, "laptop@example.com");
      EmailCredential phone = address(alice, "phone@example.com");
      HttpSession session = browser(alice, laptop.id(), NOW.minus(RECENT).minusSeconds(1));

      MockHttpServletResponse response = send("DELETE", PATH + phone.id(), session);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(WhoExceptionHandoff.HEADER))
          .isEqualTo("reauthentication-required");
      assertThat(credentials.findById(phone.id())).isPresent();
    }

    @Test
    void last_credential_is_conflict() throws Exception {
      EmailCredential only = address(alice, "only@example.com");
      HttpSession session = browser(alice, only.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + only.id(), session);

      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(credentials.findById(only.id())).isPresent();
    }

    @Test
    void api_key_beside_cookie_is_forbidden() throws Exception {
      EmailCredential laptop = address(alice, "laptop@example.com");
      EmailCredential phone = address(alice, "phone@example.com");
      MockHttpServletRequest request = new MockHttpServletRequest("DELETE", PATH + phone.id());
      request.setSession(browser(alice, laptop.id(), NOW));
      authenticateWithApiKey(alice);

      MockHttpServletResponse response = filter(request);

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
      assertThat(credentials.findById(phone.id())).isPresent();
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("DELETE", PATH + UUID.randomUUID(), null).getStatus()).isEqualTo(401);
    }
  }
}
