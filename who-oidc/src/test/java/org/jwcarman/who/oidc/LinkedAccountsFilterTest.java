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
package org.jwcarman.who.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.SignInCredentialCounter;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
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
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.nimbusds.jose.util.JSONArrayUtils;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LinkedAccountsFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration RECENT = Duration.ofMinutes(10);
  private static final String ACME_ISSUER = "https://idp.example.com";
  private static final String ACCOUNTS = "/who/me/accounts";
  private static final ClientRegistration ACME =
      ClientRegistration.withRegistrationId("acme")
          .clientId("client")
          .clientSecret("secret")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
          .scope("openid")
          .issuerUri(ACME_ISSUER)
          .authorizationUri("https://idp.example.com/authorize")
          .tokenUri("https://idp.example.com/token")
          .jwkSetUri("https://idp.example.com/jwks")
          .userNameAttributeName("sub")
          .build();

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

  /** Federated credentials, in memory, listed oldest first (in the order saved). */
  private static final class Accounts implements FederatedCredentialRepository {

    private final Links links;
    private final Map<UUID, FederatedCredential> byId = new LinkedHashMap<>();

    Accounts(Links links) {
      this.links = links;
    }

    @Override
    public Optional<FederatedCredential> findByIssuerAndSubject(String issuer, String subject) {
      return byId.values().stream()
          .filter(c -> c.issuer().equals(issuer) && c.subject().equals(subject))
          .findFirst();
    }

    @Override
    public Optional<FederatedCredential> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<FederatedCredential> findAllByIdentityId(UUID identityId) {
      return byId.values().stream()
          .filter(c -> identityId.equals(links.identityByCredential.get(c.id())))
          .toList();
    }

    @Override
    public FederatedCredential save(FederatedCredential credential) {
      byId.put(credential.id(), credential);
      return credential;
    }

    @Override
    public void deleteById(UUID id) {
      byId.remove(id);
    }
  }

  private final MutableClock clock = new MutableClock();
  private final Links links = new Links();
  private final Accounts accounts = new Accounts(links);
  private final AtomicBoolean reachedChain = new AtomicBoolean();
  private final FilterChain chain = (req, res) -> reachedChain.set(true);
  private final Identity alice = Identity.create(IdentityStatus.ACTIVE);
  private final Identity bob = Identity.create(IdentityStatus.ACTIVE);
  private FederatedCredentialService service;
  private WhoSessionSecurityContextRepository sessionRepository;
  private LinkedAccountsFilter filter;

  @BeforeEach
  void setUp() {
    IdentityRepository identities = mock(IdentityRepository.class);
    for (Identity identity : List.of(alice, bob)) {
      when(identities.findById(identity.id())).thenReturn(Optional.of(identity));
      when(identities.lockById(identity.id())).thenReturn(Optional.of(identity));
    }
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    SignInCredentialCounter counter =
        new SignInCredentialCounter() {
          @Override
          public String kind() {
            return "account";
          }

          @Override
          public long count(UUID identityId) {
            return accounts.findAllByIdentityId(identityId).size();
          }
        };
    service = new FederatedCredentialService(accounts, links, WhoEventPublisher.NOOP, clock);
    sessionRepository =
        new WhoSessionSecurityContextRepository(
            new WhoService(identities, links, i -> Set.of()),
            InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
            clock,
            WhoEventPublisher.NOOP);
    filter =
        new LinkedAccountsFilter(
            WhoPaths.defaults(),
            SignInRegistrations.of(List.of(ACME), Map.of()),
            service,
            new LastCredentialGuard(identities, List.of(counter), new TransactionTemplate(manager)),
            sessionRepository,
            new WhoExceptionHandoff(null),
            clock,
            RECENT);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private FederatedCredential account(Identity identity, String issuer, String subject) {
    return service.link(identity, issuer, subject);
  }

  /** A browser of {@code identity} that signed in at {@code at}, with the account as its factor. */
  private HttpSession browser(Identity identity, UUID credentialId, Instant at) {
    clock.set(at);
    MockHttpServletRequest request = new MockHttpServletRequest();
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of()), credentialId, SignInMethod.OIDC));
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

  private static List<Map<String, Object>> parse(MockHttpServletResponse response)
      throws Exception {
    List<Map<String, Object>> parsed = new ArrayList<>();
    for (Object element : JSONArrayUtils.parse(response.getContentAsString())) {
      Map<String, Object> row = new LinkedHashMap<>();
      ((Map<?, ?>) element).forEach((key, value) -> row.put(String.valueOf(key), value));
      parsed.add(row);
    }
    return parsed;
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_accounts_oldest_first() throws Exception {
      FederatedCredential first = account(alice, ACME_ISSUER, "first");
      FederatedCredential second = account(alice, ACME_ISSUER, "second");
      account(bob, ACME_ISSUER, "bobs");
      HttpSession session = browser(alice, first.id(), NOW);

      MockHttpServletResponse response = send("GET", ACCOUNTS, session);

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith("application/json");
      List<Map<String, Object>> body = parse(response);
      assertThat(body)
          .extracting(m -> m.get("id"))
          .containsExactly(first.id().toString(), second.id().toString());
      assertThat(body.getFirst()).containsOnlyKeys("id", "provider", "linkedAt");
      assertThat(Instant.parse((String) body.getFirst().get("linkedAt")))
          .isCloseTo(Instant.now(), within(Duration.ofMinutes(1)));
      assertThat(reachedChain.get()).isFalse();
    }

    @Test
    void provider_is_the_registration_id() throws Exception {
      FederatedCredential credential = account(alice, ACME_ISSUER, "sub");
      HttpSession session = browser(alice, credential.id(), NOW);

      assertThat(parse(send("GET", ACCOUNTS, session)))
          .extracting(m -> m.get("provider"))
          .containsExactly("acme");
    }

    @Test
    void provider_is_the_issuer_when_no_registration_has_it() throws Exception {
      FederatedCredential credential = account(alice, "https://other.example.com", "sub");
      HttpSession session = browser(alice, credential.id(), NOW);

      assertThat(parse(send("GET", ACCOUNTS, session)))
          .extracting(m -> m.get("provider"))
          .containsExactly("https://other.example.com");
    }

    @Test
    void an_api_key_alone_may_list() throws Exception {
      account(alice, ACME_ISSUER, "sub");
      account(bob, ACME_ISSUER, "bobs");
      authenticateWithApiKey(alice);

      MockHttpServletResponse response = filter(new MockHttpServletRequest("GET", ACCOUNTS));

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(parse(response)).hasSize(1);
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("GET", ACCOUNTS, null).getStatus()).isEqualTo(401);
    }
  }

  @Nested
  class Removing {

    private static final String PATH = ACCOUNTS + "/";

    @Test
    void unlinks_an_account() throws Exception {
      FederatedCredential signedInWith = account(alice, ACME_ISSUER, "laptop");
      FederatedCredential other = account(alice, ACME_ISSUER, "phone");
      HttpSession session = browser(alice, signedInWith.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + other.id(), session);

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(accounts.findById(other.id())).isEmpty();
      assertThat(links.findIdentityIdByCredentialId(other.id())).isEmpty();
      assertThat(accounts.findById(signedInWith.id())).isPresent();
      assertThat(send("GET", ACCOUNTS, session).getStatus()).isEqualTo(200);
    }

    @Test
    void unlinking_the_account_the_session_signed_in_with_ends_the_session() throws Exception {
      FederatedCredential signedInWith = account(alice, ACME_ISSUER, "laptop");
      account(alice, ACME_ISSUER, "phone");
      HttpSession session = browser(alice, signedInWith.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + signedInWith.id(), session);

      assertThat(response.getStatus()).isEqualTo(204);
      assertThat(accounts.findById(signedInWith.id())).isEmpty();
      // who re-resolves the session's credential on every request, so an unlinked account ends
      // the session: the person signs in again with the account that is left
      assertThat(send("GET", ACCOUNTS, session).getStatus()).isEqualTo(401);
    }

    @Test
    void another_identitys_id_is_not_found_and_stays_linked() throws Exception {
      FederatedCredential mine = account(alice, ACME_ISSUER, "mine");
      account(alice, ACME_ISSUER, "mine-too");
      FederatedCredential theirs = account(bob, ACME_ISSUER, "theirs");
      account(bob, ACME_ISSUER, "theirs-too");
      HttpSession session = browser(alice, mine.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + theirs.id(), session);

      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(response.getContentAsString()).isEmpty();
      assertThat(accounts.findById(theirs.id())).isPresent();
      assertThat(links.findIdentityIdByCredentialId(theirs.id())).contains(bob.id());
    }

    @Test
    void an_unknown_id_is_not_found_just_the_same() throws Exception {
      FederatedCredential mine = account(alice, ACME_ISSUER, "mine");
      account(alice, ACME_ISSUER, "mine-too");
      HttpSession session = browser(alice, mine.id(), NOW);

      assertThat(send("DELETE", PATH + UUID.randomUUID(), session).getStatus()).isEqualTo(404);
      MockHttpServletResponse notAnId = send("DELETE", PATH + "nope", session);
      assertThat(notAnId.getStatus()).isEqualTo(404);
      assertThat(notAnId.getContentAsString()).isEmpty();
    }

    @Test
    void stale_sign_in_must_reauthenticate() throws Exception {
      FederatedCredential laptop = account(alice, ACME_ISSUER, "laptop");
      FederatedCredential phone = account(alice, ACME_ISSUER, "phone");
      HttpSession session = browser(alice, laptop.id(), NOW.minus(RECENT).minusSeconds(1));

      MockHttpServletResponse response = send("DELETE", PATH + phone.id(), session);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(response.getHeader(WhoExceptionHandoff.HEADER))
          .isEqualTo("reauthentication-required");
      assertThat(accounts.findById(phone.id())).isPresent();
      assertThat(links.findIdentityIdByCredentialId(phone.id())).isPresent();
    }

    @Test
    void last_credential_is_conflict() throws Exception {
      FederatedCredential only = account(alice, ACME_ISSUER, "only");
      HttpSession session = browser(alice, only.id(), NOW);

      MockHttpServletResponse response = send("DELETE", PATH + only.id(), session);

      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(accounts.findById(only.id())).isPresent();
    }

    @Test
    void api_key_beside_cookie_is_forbidden() throws Exception {
      FederatedCredential laptop = account(alice, ACME_ISSUER, "laptop");
      FederatedCredential phone = account(alice, ACME_ISSUER, "phone");
      MockHttpServletRequest request = new MockHttpServletRequest("DELETE", PATH + phone.id());
      request.setSession(browser(alice, laptop.id(), NOW));
      authenticateWithApiKey(alice);

      MockHttpServletResponse response = filter(request);

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
      assertThat(accounts.findById(phone.id())).isPresent();
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(send("DELETE", PATH + UUID.randomUUID(), null).getStatus()).isEqualTo(401);
    }
  }

  @Test
  void starting_a_link_is_not_this_filters_request() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", ACCOUNTS + "/acme");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(reachedChain.get()).isTrue();
  }

  @Test
  void any_other_request_passes_through() throws Exception {
    List<MockHttpServletRequest> others = new ArrayList<>();
    others.add(new MockHttpServletRequest("GET", ACCOUNTS + "/acme"));
    others.add(new MockHttpServletRequest("DELETE", ACCOUNTS));
    others.add(new MockHttpServletRequest("GET", "/who/me/passkeys"));

    for (MockHttpServletRequest request : others) {
      reachedChain.set(false);
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      assertThat(reachedChain.get()).as(request.getRequestURI()).isTrue();
    }
  }
}
