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
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The events passkeys publish, through real WebAuthn ceremonies in a browser chain that applies
 * {@code passwordless()} and the passkey login method, as {@link PasskeySignInChainTest} does.
 */
@SpringBootTest(classes = PasskeyEventsTest.App.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeyEventsTest extends AbstractPasskeyTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
  private static final String ORIGIN = "https://app.test";
  private static final Duration WINDOW = Duration.ofMinutes(10);

  /** Every event who published, in order. */
  static final List<WhoEvent> EVENTS = new CopyOnWriteArrayList<>();

  /** The application's clock; a test moves it forward. */
  static final MutableClock CLOCK = new MutableClock();

  /** A clock a test can move. */
  static final class MutableClock extends Clock {

    private volatile Instant now = NOW;

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

  // Not a @Configuration: the module's other tests scan this package, and Spring Boot only skips
  // configurations nested in a class with test methods of its own; this one has only nested ones.
  @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
  @Import(WhoMeController.class)
  static class App {

    @Bean
    Clock clock() {
      return CLOCK;
    }

    @Bean
    WhoEventPublisher whoEventPublisher() {
      return EVENTS::add;
    }

    @Bean
    IdentityRepository identityRepository(JdbcClient jdbcClient) {
      return new JdbcIdentityRepository(jdbcClient);
    }

    @Bean
    CredentialIdentityRepository credentialIdentityRepository(JdbcClient jdbcClient) {
      return new JdbcCredentialIdentityRepository(jdbcClient);
    }

    @Bean
    WhoService whoService(
        IdentityRepository identityRepository,
        CredentialIdentityRepository credentialIdentityRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        WhoEventPublisher events,
        Clock clock) {
      return new WhoService(
          identityRepository,
          credentialIdentityRepository,
          i -> Set.of(),
          List.of(new PasskeyCredentialRemover(credentialRepository)),
          events,
          clock);
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
        WhoService whoService, Clock clock) {
      return new WhoSessionSecurityContextRepository(whoService, clock);
    }

    @Bean
    JdbcWhoPasskeyUserRepository passkeyUserRepository(
        JdbcClient jdbcClient,
        CredentialIdentityRepository credentialIdentityRepository,
        WhoEventPublisher events,
        Clock clock) {
      return new JdbcWhoPasskeyUserRepository(
          jdbcClient, credentialIdentityRepository, events, clock);
    }

    @Bean
    JdbcWhoPasskeyCredentialRepository passkeyCredentialRepository(
        JdbcClient jdbcClient,
        CredentialIdentityRepository credentialIdentityRepository,
        WhoEventPublisher events,
        Clock clock) {
      return new JdbcWhoPasskeyCredentialRepository(
          jdbcClient, credentialIdentityRepository, events, clock);
    }

    @Bean
    WebAuthnRelyingPartyOperations relyingPartyOperations(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        WhoEventPublisher events,
        Clock clock) {
      return new WhoRelyingPartyOperations(
          new PasskeySettings("app.test", "App", Set.of(ORIGIN), WINDOW),
          userRepository,
          credentialRepository,
          PasskeyNameResolver.identityId(),
          (identityId, label) -> {},
          events,
          clock);
    }

    @Bean
    PasskeyLoginMethod passkeyLoginMethod(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        WebAuthnRelyingPartyOperations relyingPartyOperations,
        WhoService whoService,
        WhoSessionSecurityContextRepository sessionRepository,
        JsonMapper jsonMapper,
        WhoEventPublisher events,
        Clock clock) {
      return new PasskeyLoginMethod(
          userRepository,
          credentialRepository,
          new WhoPasskeyAuthenticationProvider(
              relyingPartyOperations, credentialRepository, whoService, events, clock),
          sessionRepository,
          jsonMapper,
          clock,
          WINDOW);
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private WhoService whoService;
  @Autowired private WebAuthnRelyingPartyOperations operations;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private JdbcWhoPasskeyUserRepository userRepository;

  private final JsonMapper json = JsonMapper.builder().build();
  private final MockHttpSession session = new MockHttpSession();
  private MockMvc mvc;
  private Identity identity;
  private PasskeyEmulator device;
  private CredentialRecord registered;
  private UUID credentialUuid;

  @BeforeEach
  void setUp() {
    EVENTS.clear();
    CLOCK.set(NOW);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    identity = identityRepository.save(Identity.create());
    device = PasskeyEmulator.honest(ORIGIN);
    Authentication signedIn =
        new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of()), UUID.randomUUID());
    PublicKeyCredentialCreationOptions creationOptions =
        operations.createPublicKeyCredentialCreationOptions(
            new ImmutablePublicKeyCredentialCreationOptionsRequest(signedIn));
    // Spring's registration filter runs with the caller signed in
    SecurityContextHolder.getContext().setAuthentication(signedIn);
    try {
      registered = operations.registerCredential(device.register(creationOptions, "Laptop"));
    } finally {
      SecurityContextHolder.clearContext();
    }
    credentialUuid =
        credentialRepository.credentialUuid(registered.getCredentialId()).orElseThrow();
  }

  private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
    // as a servlet container does when the browser presents its session cookie
    return mvc.perform(
            request
                .session(session)
                .with(
                    r -> {
                      r.setRequestedSessionId(session.getId());
                      r.setRequestedSessionIdValid(true);
                      return r;
                    }))
        .andReturn()
        .getResponse();
  }

  private String requestOptions() throws Exception {
    MockHttpServletResponse options = perform(post("/webauthn/authenticate/options").with(csrf()));
    assertThat(options.getStatus()).isEqualTo(200);
    return options.getContentAsString();
  }

  private MockHttpServletResponse signIn(String assertion) throws Exception {
    return perform(
        post("/login/webauthn")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(assertion));
  }

  private MockHttpServletResponse signIn() throws Exception {
    return signIn(device.signIn(requestOptions()));
  }

  private String forged() throws Exception {
    ObjectNode assertion = (ObjectNode) json.readTree(device.signIn(requestOptions()));
    ((ObjectNode) assertion.path("response")).put("signature", Bytes.random().toBase64UrlString());
    return json.writeValueAsString(assertion);
  }

  @Nested
  class Credentials {

    @Test
    void registering_publishes_credential_added_with_the_label() {
      assertThat(EVENTS)
          .containsExactly(
              new CredentialAdded(
                  NOW, identity.id(), credentialUuid, CredentialKind.PASSKEY, "Laptop"));
    }

    @Test
    void deleting_through_springs_endpoint_publishes_credential_revoked() throws Exception {
      assertThat(signIn().getStatus()).isEqualTo(200);
      EVENTS.clear();

      MockHttpServletResponse deleted =
          perform(
              delete("/webauthn/register/" + registered.getCredentialId().toBase64UrlString())
                  .with(csrf()));

      assertThat(deleted.getStatus()).isEqualTo(204);
      assertThat(EVENTS)
          .containsExactly(
              new CredentialRevoked(
                  NOW, identity.id(), credentialUuid, CredentialKind.PASSKEY, "Laptop"));
    }
  }

  @Nested
  class Deleting_the_user_entity {

    @Test
    void publishes_credential_revoked_for_each_of_its_passkeys() {
      EVENTS.clear();

      userRepository.delete(registered.getUserEntityUserId());

      assertThat(EVENTS)
          .containsExactly(
              new CredentialRevoked(
                  NOW, identity.id(), credentialUuid, CredentialKind.PASSKEY, "Laptop"));
    }
  }

  @Nested
  class Deleting_the_identity {

    @Test
    void publishes_one_credential_revoked_for_its_passkey_with_kind_and_label() {
      EVENTS.clear();

      whoService.deleteIdentity(identity.id());

      assertThat(EVENTS)
          .filteredOn(CredentialRevoked.class::isInstance)
          .containsExactly(
              new CredentialRevoked(
                  NOW, identity.id(), credentialUuid, CredentialKind.PASSKEY, "Laptop"));
      assertThat(credentialRepository.findByCredentialId(registered.getCredentialId())).isNull();
    }
  }

  @Nested
  class Signing_in {

    @Test
    void a_passkey_sign_in_publishes_sign_in_succeeded_with_passkey() throws Exception {
      EVENTS.clear();

      assertThat(signIn().getStatus()).isEqualTo(200);

      assertThat(EVENTS)
          .containsExactly(
              new SignInSucceeded(NOW, SignInMethod.PASSKEY, identity.id(), credentialUuid));
    }

    @Test
    void a_forged_assertion_publishes_invalid_assertion() throws Exception {
      EVENTS.clear();

      assertThat(signIn(forged()).getStatus()).isEqualTo(401);

      assertThat(EVENTS)
          .singleElement()
          .isInstanceOfSatisfying(
              SignInFailed.class,
              failed -> {
                assertThat(failed.method()).isEqualTo(SignInMethod.PASSKEY);
                assertThat(failed.reason()).isEqualTo(SignInFailureReason.INVALID_ASSERTION);
                assertThat(failed.address()).isNull();
                assertThat(failed.identityId()).isNull();
                assertThat(failed.credentialId()).isNull();
              });
    }

    @Test
    void an_inactive_identity_publishes_inactive_identity() throws Exception {
      whoService.deactivate(identity.id());
      EVENTS.clear();

      assertThat(signIn().getStatus()).isEqualTo(401);

      assertThat(EVENTS)
          .singleElement()
          .isInstanceOfSatisfying(
              SignInFailed.class,
              failed -> {
                assertThat(failed.method()).isEqualTo(SignInMethod.PASSKEY);
                assertThat(failed.reason()).isEqualTo(SignInFailureReason.INACTIVE_IDENTITY);
                assertThat(failed.address()).isNull();
                assertThat(failed.identityId()).isEqualTo(identity.id());
                assertThat(failed.credentialId()).isEqualTo(credentialUuid);
              });
    }
  }

  @Nested
  class Registering_late {

    @Test
    void a_stale_session_adding_a_passkey_publishes_registration_refused() throws Exception {
      assertThat(signIn().getStatus()).isEqualTo(200);
      Instant later = NOW.plus(WINDOW).plusSeconds(1);
      CLOCK.set(later);
      EVENTS.clear();

      MockHttpServletResponse refused = perform(post("/webauthn/register/options").with(csrf()));

      assertThat(refused.getStatus()).isEqualTo(401);
      assertThat(EVENTS)
          .containsExactly(
              new PasskeyRegistrationRefused(
                  later,
                  identity.id(),
                  PasskeyRegistrationRefusedReason.REAUTHENTICATION_REQUIRED));
    }
  }
}
