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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.RecordingSender;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.signup.ClosedSignUpPolicy;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.core.signup.SignUpRequest;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.test.StubOidcProvider;
import org.jwcarman.who.test.WhoMockMvcBrowser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/** One application for the provider sign-in tests: a stub provider registered as {@code test}. */
@SpringBootTest(
    classes = AbstractOidcTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.enabled=false",
      "who.jwt.enabled=false",
      "who.oidc.enabled=true",
      "who.oidc.success-url=" + AbstractOidcTest.SUCCESS_URL,
      "who.oidc.failure-url=" + AbstractOidcTest.FAILURE_URL,
      // WhoMockMvcBrowser keeps a MockHttpSession, whose attributes these tests read
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
      "spring.security.oauth2.client.registration.test.client-id=client",
      "spring.security.oauth2.client.registration.test.client-secret=secret",
      "spring.security.oauth2.client.registration.test.scope=openid,email,profile"
    })
@Testcontainers
@RecordApplicationEvents
abstract class AbstractOidcTest {

  static final StubOidcProvider PROVIDER = StubOidcProvider.start();
  static final String SUCCESS_URL = "/home";
  static final String FAILURE_URL = "/signin";
  static final Duration LINK_WINDOW = Duration.ofMinutes(10);

  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
    registry.add("spring.security.oauth2.client.provider.test.issuer-uri", PROVIDER::issuer);
  }

  /** The sign-up policy, which a test may swap; closed unless it does. */
  static final class SwitchablePolicy implements WhoSignUpPolicy {

    private final AtomicReference<WhoSignUpPolicy> delegate =
        new AtomicReference<>(new ClosedSignUpPolicy());

    void set(WhoSignUpPolicy policy) {
      delegate.set(policy);
    }

    @Override
    public SignUpDecision decide(SignUpRequest request) {
      return delegate.get().decide(request);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    RecordingSender recordingSender() {
      return new RecordingSender();
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    @Bean
    SwitchablePolicy switchablePolicy() {
      return new SwitchablePolicy();
    }

    @Bean
    RecordingAuthorizedClients recordingAuthorizedClients() {
      return new RecordingAuthorizedClients();
    }

    /** An application chain in place of who's default one, when a test asks for it. */
    @Bean
    @ConditionalOnBooleanProperty("test.application-chain")
    SecurityFilterChain applicationChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  /** The application's own authorized-client repository, which who's sign-in must not use. */
  static final class RecordingAuthorizedClients implements OAuth2AuthorizedClientRepository {

    final AtomicInteger calls = new AtomicInteger();

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String clientRegistrationId, Authentication principal, HttpServletRequest request) {
      calls.incrementAndGet();
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient authorizedClient,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {
      calls.incrementAndGet();
    }

    @Override
    public void removeAuthorizedClient(
        String clientRegistrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {
      calls.incrementAndGet();
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcClient jdbcClient;
  @Autowired IdentityRepository identityRepository;
  @Autowired FederatedCredentialService federatedCredentialService;
  @Autowired FederatedCredentialRepository federatedCredentialRepository;
  @Autowired WhoService whoService;
  @Autowired SwitchablePolicy policy;
  @Autowired RecordingSender sender;
  @Autowired ApplicationEvents events;
  @Autowired RecordingAuthorizedClients recordingAuthorizedClients;

  WhoMockMvcBrowser browser;
  Identity alice;
  FederatedCredential aliceCredential;

  @BeforeEach
  void resetOidc() {
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    PROVIDER.nextIssuerClaim(null);
    policy.set(new ClosedSignUpPolicy());
    recordingAuthorizedClients.calls.set(0);
    sender.sent.clear();
    alice = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    aliceCredential = federatedCredentialService.link(alice, PROVIDER.issuer(), "alice");
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    browser = new WhoMockMvcBrowser(mvc);
  }

  /** Starts sign-in at the app, lets the provider redirect back, and returns the callback. */
  MvcResult signInAtProvider(String subject, Map<String, Object> claims) throws Exception {
    PROVIDER.nextUser(subject, claims);
    String authorize = startAuthorization();
    HttpResponse<Void> atProvider =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(authorize)).build(),
            HttpResponse.BodyHandlers.discarding());
    String callback = atProvider.headers().firstValue("Location").orElseThrow();
    return browser.perform(get(URI.create(callback))).andReturn();
  }

  String startAuthorization() throws Exception {
    MvcResult start = browser.perform(get("/oauth2/authorization/test")).andReturn();
    String authorize = start.getResponse().getRedirectedUrl();
    assertThat(authorize).startsWith(PROVIDER.issuer() + "/authorize");
    return authorize;
  }

  MvcResult signInAsAlice() throws Exception {
    return signInAtProvider("alice", Map.of("email", "alice@example.com", "email_verified", true));
  }

  /** Signs the browser in by email link, a real sign-in that keeps the session's attributes. */
  void signInByEmailLink(String address) throws Exception {
    browser.perform(
        post("/ott/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + address + "\"}"));
    String token = sender.sent.getLast().token();
    MvcResult result =
        browser
            .perform(
                post("/login/ott")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + token + "\"}"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  Identity signedInIdentity(String issuer, String subject) {
    Identity identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    FederatedCredential credential = federatedCredentialService.link(identity, issuer, subject);
    browser.signedInAs(identity.id(), credential.id());
    return identity;
  }

  int me() throws Exception {
    return browser.perform(get("/who/me")).andReturn().getResponse().getStatus();
  }

  Object sessionAttribute(String name) {
    HttpSession session = browser.session();
    return session == null ? null : session.getAttribute(name);
  }
}
