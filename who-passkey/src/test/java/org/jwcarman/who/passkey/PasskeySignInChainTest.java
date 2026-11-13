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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.jdbc.JdbcCredentialIdentityRepository;
import org.jwcarman.who.jdbc.JdbcIdentityRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.SignInFailedException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.MapPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.MapUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Passkey sign-in over HTTP, through Spring's WebAuthn endpoints in a browser chain that applies
 * {@code passwordless()} and the passkey login method. Every test builds its own context, so the
 * chain is assembled by the test that checks it.
 *
 * <p>The application also has WebAuthn repositories of its own: the chain must still use who's.
 */
@SpringBootTest(classes = PasskeySignInChainTest.App.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeySignInChainTest extends AbstractPasskeyTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final String ORIGIN = "https://app.test";
  private static final Duration WINDOW = Duration.ofMinutes(10);

  /** What who handed to the application, in order. */
  static final List<Exception> HANDED_OFF = new CopyOnWriteArrayList<>();

  /** Successful authentications the application heard about. */
  static final List<AuthenticationSuccessEvent> SUCCESSES = new CopyOnWriteArrayList<>();

  /** Failed authentications the application heard about. */
  static final List<AbstractAuthenticationFailureEvent> FAILURES = new CopyOnWriteArrayList<>();

  @Configuration
  @EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
  @Import(WhoMeController.class)
  static class App {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
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
        CredentialIdentityRepository credentialIdentityRepository) {
      return new WhoService(identityRepository, credentialIdentityRepository, i -> Set.of());
    }

    @Bean
    WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
        WhoService whoService, Clock clock) {
      return new WhoSessionSecurityContextRepository(whoService, clock);
    }

    @Bean
    WhoExceptionHandoff whoExceptionHandoff() {
      // records, then lets who fall back to the bare status
      return new WhoExceptionHandoff((request, response, exception) -> !HANDED_OFF.add(exception));
    }

    @Bean
    JdbcWhoPasskeyUserRepository passkeyUserRepository(
        JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
      return new JdbcWhoPasskeyUserRepository(jdbcClient, credentialIdentityRepository);
    }

    @Bean
    JdbcWhoPasskeyCredentialRepository passkeyCredentialRepository(
        JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
      return new JdbcWhoPasskeyCredentialRepository(jdbcClient, credentialIdentityRepository);
    }

    /** The application's own user entities, unrelated to who's. */
    @Bean
    PublicKeyCredentialUserEntityRepository appUserEntities() {
      return new MapPublicKeyCredentialUserEntityRepository();
    }

    /** The application's own credential records, unrelated to who's. */
    @Bean
    UserCredentialRepository appUserCredentials() {
      return new MapUserCredentialRepository();
    }

    @Bean
    WebAuthnRelyingPartyOperations relyingPartyOperations(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository) {
      return new WhoRelyingPartyOperations(
          new PasskeySettings("app.test", "App", Set.of(ORIGIN), WINDOW),
          userRepository,
          credentialRepository,
          PasskeyNameResolver.identityId(),
          (identityId, label) -> {});
    }

    @Bean
    @Primary
    AuthenticationEventPublisher authenticationEventPublisher() {
      return new DefaultAuthenticationEventPublisher();
    }

    @Bean
    ApplicationListener<AuthenticationSuccessEvent> successListener() {
      return SUCCESSES::add;
    }

    @Bean
    ApplicationListener<AbstractAuthenticationFailureEvent> failureListener() {
      return FAILURES::add;
    }

    @Bean
    PasskeyLoginMethod passkeyLoginMethod(
        JdbcWhoPasskeyUserRepository userRepository,
        JdbcWhoPasskeyCredentialRepository credentialRepository,
        WebAuthnRelyingPartyOperations relyingPartyOperations,
        WhoService whoService,
        WhoSessionSecurityContextRepository sessionRepository,
        JsonMapper jsonMapper,
        Clock clock) {
      return new PasskeyLoginMethod(
          userRepository,
          credentialRepository,
          new WhoPasskeyAuthenticationProvider(
              relyingPartyOperations, credentialRepository, whoService),
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
  @Autowired private WebAuthnRelyingPartyOperations operations;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;

  private final JsonMapper json = JsonMapper.builder().build();
  private final MockHttpSession session = new MockHttpSession();
  private MockMvc mvc;
  private Identity identity;
  private PasskeyEmulator device;
  private CredentialRecord registered;

  @BeforeEach
  void setUp() {
    HANDED_OFF.clear();
    SUCCESSES.clear();
    FAILURES.clear();
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

  @Test
  void a_verified_passkey_signs_in_and_answers_with_the_identity() throws Exception {
    MockHttpServletResponse signedIn = signIn();

    assertThat(signedIn.getStatus()).isEqualTo(200);
    assertThat(signedIn.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(signedIn.getContentAsString())
        .isEqualTo("{\"identityId\":\"" + identity.id() + "\"}");
  }

  @Test
  void a_verified_passkey_keeps_the_browser_signed_in_on_later_requests() throws Exception {
    assertThat(perform(get("/who/me")).getStatus()).isEqualTo(401);

    signIn();

    MockHttpServletResponse me = perform(get("/who/me"));
    assertThat(me.getStatus()).isEqualTo(200);
    assertThat(json.readTree(me.getContentAsString()).path("identityId").asString())
        .isEqualTo(identity.id().toString());
  }

  @Test
  void a_verified_passkey_sign_in_changes_the_session_id_against_session_fixation()
      throws Exception {
    String options = requestOptions();
    String before = session.getId();

    assertThat(signIn(device.signIn(options)).getStatus()).isEqualTo(200);

    assertThat(session.getId()).isNotEqualTo(before);
  }

  @Test
  void a_verified_passkey_sign_in_publishes_an_authentication_success_event() throws Exception {
    signIn();

    assertThat(SUCCESSES)
        .singleElement()
        .extracting(e -> e.getAuthentication().getName())
        .isEqualTo(identity.id().toString());
  }

  private String forged() throws Exception {
    ObjectNode assertion = (ObjectNode) json.readTree(device.signIn(requestOptions()));
    ((ObjectNode) assertion.path("response")).put("signature", Bytes.random().toBase64UrlString());
    return json.writeValueAsString(assertion);
  }

  @Test
  void a_forged_assertion_is_handed_to_the_application_as_a_failed_sign_in() throws Exception {
    MockHttpServletResponse refused = signIn(forged());

    assertThat(refused.getStatus()).isEqualTo(401);
    assertThat(HANDED_OFF).singleElement().isInstanceOf(SignInFailedException.class);
    assertThat(perform(get("/who/me")).getStatus()).isEqualTo(401);
  }

  @Test
  void a_forged_assertion_publishes_an_authentication_failure_event() throws Exception {
    signIn(forged());

    assertThat(FAILURES).hasSize(1);
    assertThat(SUCCESSES).isEmpty();
  }

  @Test
  void the_owner_can_delete_their_passkey_although_the_app_has_its_own_webauthn_repositories()
      throws Exception {
    assertThat(signIn().getStatus()).isEqualTo(200);

    MockHttpServletResponse deleted =
        perform(
            delete("/webauthn/register/" + registered.getCredentialId().toBase64UrlString())
                .with(csrf()));

    assertThat(deleted.getStatus()).isEqualTo(204);
    assertThat(credentialRepository.findByCredentialId(registered.getCredentialId())).isNull();
  }
}
