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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.oidc.OidcLoginMethod;
import org.jwcarman.who.oidc.OidcRedirects;
import org.jwcarman.who.oidc.PendingSignUp;
import org.jwcarman.who.oidc.PendingSignUps;
import org.jwcarman.who.oidc.SignInRegistrations;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.test.StubOidcProvider;
import org.jwcarman.who.test.WhoMockMvcBrowser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
    classes = OidcSignInTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.enabled=false",
      "who.passkey.enabled=false",
      "who.jwt.enabled=false",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration",
      "spring.security.oauth2.client.registration.test.client-id=client",
      "spring.security.oauth2.client.registration.test.client-secret=secret",
      "spring.security.oauth2.client.registration.test.scope=openid,email,profile"
    })
@Testcontainers
@RecordApplicationEvents
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcSignInTest {

  static final StubOidcProvider PROVIDER = StubOidcProvider.start();
  static final String SUCCESS_URL = "/home";
  static final String FAILURE_URL = "/signin";

  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
    registry.add("spring.security.oauth2.client.provider.test.issuer-uri", PROVIDER::issuer);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    // Task 9 replaces this with WhoOidcAutoConfiguration
    @Bean
    OidcLoginMethod oidcLoginMethod(
        InMemoryClientRegistrationRepository clients,
        WhoService whoService,
        FederatedCredentialRepository credentials,
        WhoSessionSecurityContextRepository sessions,
        Clock clock) {
      return new OidcLoginMethod(
          SignInRegistrations.of(clients, Map.of()),
          whoService,
          credentials,
          sessions,
          new OidcRedirects(SUCCESS_URL, FAILURE_URL),
          clock,
          Duration.ofMinutes(10));
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private FederatedCredentialRepository federatedCredentialRepository;
  @Autowired private WhoService whoService;
  @Autowired private ApplicationEvents events;

  private WhoMockMvcBrowser browser;
  private Identity alice;
  private FederatedCredential aliceCredential;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    PROVIDER.nextIssuerClaim(null);
    alice = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    aliceCredential = federatedCredentialService.link(alice, PROVIDER.issuer(), "alice");
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    browser = new WhoMockMvcBrowser(mvc);
  }

  /** Starts sign-in at the app, lets the provider redirect back, and returns the callback. */
  private MvcResult signInAtProvider(String subject, Map<String, Object> claims) throws Exception {
    PROVIDER.nextUser(subject, claims);
    String authorize = startAuthorization();
    HttpResponse<Void> atProvider =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(authorize)).build(),
            HttpResponse.BodyHandlers.discarding());
    String callback = atProvider.headers().firstValue("Location").orElseThrow();
    return browser.perform(get(URI.create(callback))).andReturn();
  }

  private String startAuthorization() throws Exception {
    MvcResult start = browser.perform(get("/oauth2/authorization/test")).andReturn();
    String authorize = start.getResponse().getRedirectedUrl();
    assertThat(authorize).startsWith(PROVIDER.issuer() + "/authorize");
    return authorize;
  }

  private MvcResult signInAsAlice() throws Exception {
    return signInAtProvider("alice", Map.of("email", "alice@example.com", "email_verified", true));
  }

  private int me() throws Exception {
    return browser.perform(get("/who/me")).andReturn().getResponse().getStatus();
  }

  private Object sessionAttribute(String name) {
    HttpSession session = browser.session();
    return session == null ? null : session.getAttribute(name);
  }

  private Identity signedInIdentity(String issuer, String subject) {
    Identity identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    FederatedCredential credential = federatedCredentialService.link(identity, issuer, subject);
    browser.signedInAs(identity.id(), credential.id());
    return identity;
  }

  @Nested
  class Known_account {

    @Test
    void signs_in_and_redirects_to_the_success_url() throws Exception {
      MvcResult result = signInAsAlice();

      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS_URL);
      assertThat(me()).isEqualTo(200);
      assertThat(sessionAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
          .isInstanceOfSatisfying(
              WhoSession.class, s -> assertThat(s.identityId()).isEqualTo(alice.id()));
    }

    @Test
    void publishes_sign_in_succeeded_with_oidc() throws Exception {
      signInAsAlice();

      assertThat(events.stream(SignInSucceeded.class))
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.method()).isEqualTo(SignInMethod.OIDC);
                assertThat(e.identityId()).isEqualTo(alice.id());
                assertThat(e.credentialId()).isEqualTo(aliceCredential.id());
              });
    }

    @Test
    void sets_the_last_sign_in_cookie_to_the_registration_id() throws Exception {
      MvcResult result = signInAsAlice();

      assertThat(result.getResponse().getCookie("who_last_sign_in"))
          .extracting(Cookie::getValue)
          .isEqualTo("test");
    }

    @Test
    void keeps_no_oauth2_token_in_the_session() throws Exception {
      signInAsAlice();

      HttpSession session = browser.session();
      for (String name : Collections.list(session.getAttributeNames())) {
        assertThat(session.getAttribute(name))
            .as(name)
            .isNotInstanceOfAny(
                OAuth2AuthenticationToken.class, OAuth2User.class, OAuth2AuthorizedClient.class);
      }
      assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
    }

    @Test
    void same_identity_signs_in_again_without_a_new_session() throws Exception {
      signInAsAlice();
      browser.session().setAttribute("app.marker", "kept");

      MvcResult again = signInAsAlice();

      assertThat(again.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS_URL);
      assertThat(sessionAttribute("app.marker")).isEqualTo("kept");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(me()).isEqualTo(200);
    }
  }

  @Nested
  class Unknown_account {

    @Test
    void creates_nothing_and_redirects_with_no_account() throws Exception {
      long identities =
          jdbcClient.sql("SELECT count(*) FROM who_identity").query(Long.class).single();

      MvcResult result = signInAtProvider("carol", Map.of("email", "carol@example.com"));

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=no-account");
      assertThat(jdbcClient.sql("SELECT count(*) FROM who_identity").query(Long.class).single())
          .isEqualTo(identities);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
      assertThat(me()).isEqualTo(401);
    }

    @Test
    void stores_a_pending_sign_up() throws Exception {
      signInAtProvider("carol", Map.of("email", "carol@example.com", "hd", "example.com"));

      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE))
          .isInstanceOfSatisfying(
              PendingSignUp.class,
              p -> {
                assertThat(p.registrationId()).isEqualTo("test");
                assertThat(p.issuer()).isEqualTo(PROVIDER.issuer());
                assertThat(p.subject()).isEqualTo("carol");
                assertThat(p.displayName()).isEqualTo("carol@example.com");
                assertThat(p.verifiedEmail()).isNull();
                assertThat(p.claims()).containsEntry("hd", "example.com");
                assertThat(p.createdFor()).isNull();
              });
    }

    @Test
    void while_signed_in_is_linkable_only() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");

      MvcResult result = signInAtProvider("carol", Map.of());

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=no-account");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE))
          .isInstanceOfSatisfying(
              PendingSignUp.class, p -> assertThat(p.createdFor()).isEqualTo(bob.id()));
      assertThat(me()).isEqualTo(200);
    }

    @Test
    void linked_to_another_identity_while_signed_in_redirects_already_linked() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");

      MvcResult result = signInAsAlice();

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=already-linked");
      assertThat(sessionAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
          .isInstanceOfSatisfying(
              WhoSession.class, s -> assertThat(s.identityId()).isEqualTo(bob.id()));
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(events.stream(SignInSucceeded.class)).isEmpty();
    }
  }

  @Nested
  class Failures {

    @Test
    void inactive_identity_is_signin_failed() throws Exception {
      whoService.deactivate(alice.id());

      MvcResult result = signInAsAlice();

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(events.stream(SignInFailed.class))
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.method()).isEqualTo(SignInMethod.OIDC);
                assertThat(e.reason()).isEqualTo(SignInFailureReason.INACTIVE_IDENTITY);
                assertThat(e.identityId()).isEqualTo(alice.id());
              });
      assertThat(me()).isEqualTo(401);
    }

    @Test
    void provider_error_is_signin_failed_with_provider_error_reason() throws Exception {
      String authorize = startAuthorization();
      String state =
          UriComponentsBuilder.fromUriString(authorize).build().getQueryParams().getFirst("state");

      MvcResult result =
          browser
              .perform(
                  get("/login/oauth2/code/test")
                      .param("error", "access_denied")
                      .param("state", state))
              .andReturn();

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(events.stream(SignInFailed.class))
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo(SignInFailureReason.PROVIDER_ERROR));
    }

    @Test
    void forged_issuer_is_rejected() throws Exception {
      PROVIDER.nextIssuerClaim("https://accounts.google.com");

      MvcResult result = signInAsAlice();

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(events.stream(SignInFailed.class))
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo(SignInFailureReason.PROVIDER_ERROR));
      assertThat(me()).isEqualTo(401);
    }
  }

  @Test
  void select_account_is_requested_only_when_signed_in() throws Exception {
    assertThat(startAuthorization()).doesNotContain("prompt=");

    signedInIdentity("https://other.example.com", "bob");

    assertThat(startAuthorization()).contains("prompt=select_account");
  }

  @Test
  void a_registration_who_does_not_sign_in_with_cannot_start() throws Exception {
    MvcResult result = browser.perform(get("/oauth2/authorization/unknown")).andReturn();

    assertThat(result.getResponse().getRedirectedUrl()).isNull();
    assertThat(result.getResponse().getStatus()).isEqualTo(404);
  }
}
