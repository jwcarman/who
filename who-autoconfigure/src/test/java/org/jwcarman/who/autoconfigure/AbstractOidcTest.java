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
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.core.signup.ClosedSignUpPolicy;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.core.signup.SignUpRequest;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.oidc.UnknownProviderException;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.test.StubOidcProvider;
import org.jwcarman.who.test.WhoMockMvcBrowser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
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
      "who.oidc.link-url=" + AbstractOidcTest.LINK_URL,
      // WhoMockMvcBrowser keeps a MockHttpSession, whose attributes these tests read
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
      "spring.security.oauth2.client.registration.test.client-id=client",
      "spring.security.oauth2.client.registration.test.client-secret=secret",
      "spring.security.oauth2.client.registration.test.scope=openid,email,profile",
      // a plain OAuth 2 provider, with no ID token
      "spring.security.oauth2.client.registration.plain.client-id=client",
      "spring.security.oauth2.client.registration.plain.client-secret=secret",
      "spring.security.oauth2.client.registration.plain.authorization-grant-type=authorization_code",
      "spring.security.oauth2.client.registration.plain.scope=read:user",
      "spring.security.oauth2.client.registration.plain.redirect-uri={baseUrl}/{action}/oauth2/code/{registrationId}",
      "spring.security.oauth2.client.provider.plain.user-name-attribute=id",
      "who.oidc.providers.plain.subject-attribute=id"
    })
@Testcontainers
@RecordApplicationEvents
abstract class AbstractOidcTest {

  static final StubOidcProvider PROVIDER = StubOidcProvider.start();
  static final StubOidcProvider PLAIN = StubOidcProvider.startGitHub();
  static final String SUCCESS_URL = "/home";
  static final String FAILURE_URL = "/signin";
  static final String LINK_URL = "/settings/accounts";
  static final Duration LINK_WINDOW = Duration.ofMinutes(10);

  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
    registry.add("spring.security.oauth2.client.provider.test.issuer-uri", PROVIDER::issuer);
    registry.add(
        "spring.security.oauth2.client.provider.plain.authorization-uri",
        () -> PLAIN.issuer() + "/authorize");
    registry.add(
        "spring.security.oauth2.client.provider.plain.token-uri", () -> PLAIN.issuer() + "/token");
    registry.add(
        "spring.security.oauth2.client.provider.plain.user-info-uri",
        () -> PLAIN.issuer() + "/user");
    registry.add("who.oidc.providers.plain.issuer", PLAIN::issuer);
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

    @Bean
    UnknownProviderAdvice unknownProviderAdvice() {
      return new UnknownProviderAdvice();
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

  /** The application's own answer to an unknown provider, which shows the exception reached it. */
  @RestControllerAdvice
  static final class UnknownProviderAdvice {

    @ExceptionHandler(UnknownProviderException.class)
    ProblemDetail unknownProvider(UnknownProviderException e) {
      return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "from the advice");
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
  @Autowired WhoSessions whoSessions;
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
    return finishAtProvider(startAuthorization());
  }

  /** Follows an authorization URL at the provider and returns the app's callback. */
  MvcResult finishAtProvider(String authorize) throws Exception {
    return browser.perform(get(URI.create(callbackFrom(authorize)))).andReturn();
  }

  /** Follows an authorization URL at the provider and returns where it sends the browser back. */
  String callbackFrom(String authorize) throws Exception {
    HttpResponse<Void> atProvider =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(authorize)).build(),
            HttpResponse.BodyHandlers.discarding());
    return atProvider.headers().firstValue("Location").orElseThrow();
  }

  /** Starts a link at the app, lets the provider redirect back, and returns the callback. */
  MvcResult linkAtProvider(String subject, Map<String, Object> claims) throws Exception {
    PROVIDER.nextUser(subject, claims);
    return finishAtProvider(startLink());
  }

  String startLink() throws Exception {
    return startLink("test", PROVIDER);
  }

  /** Starts a link through {@code registrationId}, served by {@code provider}. */
  String startLink(String registrationId, StubOidcProvider provider) throws Exception {
    MvcResult start = browser.perform(post("/who/oidc/link/" + registrationId)).andReturn();
    String authorize = start.getResponse().getRedirectedUrl();
    assertThat(authorize).startsWith(provider.issuer() + "/authorize");
    return authorize;
  }

  /** The {@code redirect_uri} an authorization URL sends the provider, decoded. */
  static String redirectUri(String authorize) {
    return URLDecoder.decode(
        UriComponentsBuilder.fromUriString(authorize)
            .build()
            .getQueryParams()
            .getFirst("redirect_uri"),
        StandardCharsets.UTF_8);
  }

  String startAuthorization() throws Exception {
    MvcResult start = browser.perform(get("/who/oidc/authorize/test")).andReturn();
    String authorize = start.getResponse().getRedirectedUrl();
    assertThat(authorize).startsWith(PROVIDER.issuer() + "/authorize");
    // the provider sends the browser back to who's callback, under the root
    assertThat(redirectUri(authorize)).isEqualTo("http://localhost/who/oidc/callback/test");
    return authorize;
  }

  MvcResult signInAsAlice() throws Exception {
    return signInAtProvider("alice", Map.of("email", "alice@example.com", "email_verified", true));
  }

  /** Signs the browser in by email link, a real sign-in that keeps the session's attributes. */
  void signInByEmailLink(String address) throws Exception {
    browser.perform(
        post("/who/email/send")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + address + "\"}"));
    String token = sender.sent.getLast().token();
    MvcResult result =
        browser
            .perform(
                post("/who/email/sign-in")
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

  /** Signs another identity in on the same browser session, keeping what the session holds. */
  Identity signedInIdentityKeepingSession(String issuer, String subject) {
    Identity identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    FederatedCredential credential = federatedCredentialService.link(identity, issuer, subject);
    SessionRecord row = whoSessions.start(identity.id(), credential.id(), SignInMethod.OIDC, null);
    HttpSession session = browser.session();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(
            identity.id(), credential.id(), row.signedInAt(), SignInMethod.OIDC, row.id()));
    session.setAttribute(
        WhoSessionSecurityContextRepository.PRINCIPAL_INDEX_ATTRIBUTE, identity.id().toString());
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
