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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.IdentityCreated;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.oidc.PendingSignUp;
import org.jwcarman.who.oidc.PendingSignUps;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PendingSignUpEndpointsTest extends AbstractOidcTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private EmailCredentialService emailCredentialService;

  private MvcResult send(String method, String url) throws Exception {
    return browser.perform("GET".equals(method) ? get(url) : post(url)).andReturn();
  }

  private static JsonNode body(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  private long identities() {
    return jdbcClient.sql("SELECT count(*) FROM who_identity").query(Long.class).single();
  }

  private void plant(String subject, UUID createdFor) {
    browser
        .session()
        .setAttribute(
            PendingSignUps.ATTRIBUTE,
            new PendingSignUp(
                "test",
                PROVIDER.issuer(),
                subject,
                subject,
                null,
                Map.of(),
                createdFor,
                Instant.now().plus(Duration.ofMinutes(10))));
  }

  private List<Class<?>> eventsAbout(UUID identityId) {
    return events.stream()
        .map(e -> e instanceof PayloadApplicationEvent<?> p ? p.getPayload() : e)
        .filter(WhoEvent.class::isInstance)
        .filter(
            e ->
                switch (e) {
                  case IdentityCreated c -> c.identityId().equals(identityId);
                  case CredentialAdded c -> c.identityId().equals(identityId);
                  case SignInSucceeded c -> c.identityId().equals(identityId);
                  default -> false;
                })
        .<Class<?>>map(Object::getClass)
        .toList();
  }

  @Nested
  class Pending {

    @Test
    void describes_a_pending_sign_up_for_the_page() throws Exception {
      signInAtProvider("carol", Map.of("email", "carol@example.com"));

      MvcResult result = send("GET", "/who/oidc/pending");

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode body = body(result);
      assertThat(body.get("provider").asString()).isEqualTo("test");
      assertThat(body.get("displayName").asString()).isEqualTo("carol@example.com");
      assertThat(body.get("canSignUp").asBoolean()).isTrue();
      assertThat(body.get("canLink").asBoolean()).isFalse();
    }

    @Test
    void a_linkable_one_says_so() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      linkAtProvider("carol", Map.of());

      JsonNode body = body(send("GET", "/who/oidc/pending"));

      assertThat(body.get("canSignUp").asBoolean()).isFalse();
      assertThat(body.get("canLink").asBoolean()).isTrue();
    }

    @Test
    void no_pending_is_404() throws Exception {
      assertThat(send("GET", "/who/oidc/pending").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void planted_pending_is_dropped_when_the_victim_signs_in() throws Exception {
      emailCredentialService.register(alice, "alice@example.com");
      signInAtProvider("mallory", Map.of());

      signInByEmailLink("alice@example.com");

      assertThat(me()).isEqualTo(200);
      assertThat(send("GET", "/who/oidc/pending").getResponse().getStatus()).isEqualTo(404);
      assertThat(send("POST", "/who/oidc/link").getResponse().getStatus()).isEqualTo(404);
    }
  }

  @Nested
  class Sign_up {

    @Test
    void closed_policy_refuses_with_403_and_creates_nothing() throws Exception {
      signInAtProvider("carol", Map.of());
      long before = identities();

      MvcResult result = send("POST", "/who/oidc/sign-up");

      assertThat(result.getResponse().getStatus()).isEqualTo(403);
      assertThat(identities()).isEqualTo(before);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
      assertThat(events.stream(SignInFailed.class))
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo(SignInFailureReason.SIGN_UP_REFUSED));
      assertThat(me()).isEqualTo(401);
    }

    @Test
    void
        allowing_policy_creates_signs_in_and_publishes_identity_created_credential_added_sign_in_succeeded_in_that_order()
            throws Exception {
      policy.set(request -> SignUpDecision.allow());
      signInAtProvider("carol", Map.of());

      MvcResult result = send("POST", "/who/oidc/sign-up");

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      UUID identityId = UUID.fromString(body(result).get("identityId").asString());
      assertThat(eventsAbout(identityId))
          .containsExactly(IdentityCreated.class, CredentialAdded.class, SignInSucceeded.class);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isPresent();
      assertThat(result.getResponse().getCookie("who_last_sign_in"))
          .extracting(Cookie::getValue)
          .isEqualTo("test");
      assertThat(me()).isEqualTo(200);
    }

    @Test
    void sign_up_runs_the_policy_setup_in_the_same_transaction() throws Exception {
      policy.set(
          request ->
              SignUpDecision.allow(
                  identity -> {
                    throw new IllegalStateException("setup failed");
                  }));
      signInAtProvider("carol", Map.of());
      long before = identities();

      assertThatThrownBy(() -> send("POST", "/who/oidc/sign-up"))
          .satisfies(
              e ->
                  assertThat(NestedExceptionUtils.getMostSpecificCause(e))
                      .hasMessage("setup failed"));

      assertThat(identities()).isEqualTo(before);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void sign_up_without_csrf_is_403() throws Exception {
      policy.set(request -> SignUpDecision.allow());
      signInAtProvider("carol", Map.of());

      MvcResult result = browser.performWithoutCsrf(post("/who/oidc/sign-up")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(403);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void sign_up_while_signed_in_is_403() throws Exception {
      policy.set(request -> SignUpDecision.allow());
      signedInIdentity("https://other.example.com", "bob");
      plant("carol", null);

      assertThat(send("POST", "/who/oidc/sign-up").getResponse().getStatus()).isEqualTo(403);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void pending_is_single_use() throws Exception {
      signInAtProvider("carol", Map.of());
      assertThat(send("POST", "/who/oidc/sign-up").getResponse().getStatus()).isEqualTo(403);

      policy.set(request -> SignUpDecision.allow());

      assertThat(send("POST", "/who/oidc/sign-up").getResponse().getStatus()).isEqualTo(404);
    }
  }

  @Nested
  class Link {

    @Test
    void link_adds_the_account_to_the_signed_in_identity() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      linkAtProvider("carol", Map.of());

      MvcResult result = send("POST", "/who/oidc/link");

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      FederatedCredential carol =
          federatedCredentialRepository
              .findByIssuerAndSubject(PROVIDER.issuer(), "carol")
              .orElseThrow();
      assertThat(body(result).get("credentialId").asString()).isEqualTo(carol.id().toString());
      assertThat(whoService.resolve(carol))
          .hasValueSatisfying(p -> assertThat(p.identity().id()).isEqualTo(bob.id()));
      assertThat(events.stream(CredentialAdded.class))
          .anySatisfy(e -> assertThat(e.credentialId()).isEqualTo(carol.id()));
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
    }

    @Test
    void link_after_the_window_requires_reauthentication() throws Exception {
      Identity bob = identityRepository.save(Identity.create());
      FederatedCredential bobs =
          federatedCredentialService.link(bob, "https://other.example.com", "bob");
      browser.signedInAs(bob.id(), bobs.id());
      linkAtProvider("carol", Map.of());
      // the sign-in grows old between the provider's answer and the confirmation; the session
      // itself stays live
      WhoSession live =
          (WhoSession)
              browser.session().getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);
      browser
          .session()
          .setAttribute(
              WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
              new WhoSession(
                  bob.id(),
                  bobs.id(),
                  Instant.now().minus(LINK_WINDOW).minusSeconds(60),
                  live.signInMethod(),
                  live.sessionId()));
      assertThat(me()).isEqualTo(200);

      assertThat(send("POST", "/who/oidc/link").getResponse().getStatus()).isEqualTo(401);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void link_without_csrf_is_403() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      linkAtProvider("carol", Map.of());

      MvcResult result = browser.performWithoutCsrf(post("/who/oidc/link")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(403);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void link_by_another_identity_is_404() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      signedInIdentity("https://other.example.com", "dave");
      plant("carol", bob.id());

      assertThat(send("POST", "/who/oidc/link").getResponse().getStatus()).isEqualTo(404);
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void link_of_an_account_linked_elsewhere_is_409() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      plant("alice", bob.id());

      assertThat(send("POST", "/who/oidc/link").getResponse().getStatus()).isEqualTo(409);
      assertThat(whoService.resolve(aliceCredential))
          .hasValueSatisfying(p -> assertThat(p.identity().id()).isEqualTo(alice.id()));
    }

    @Test
    void link_signed_out_is_401() throws Exception {
      plant("carol", null);

      assertThat(send("POST", "/who/oidc/link").getResponse().getStatus()).isEqualTo(401);
    }
  }
}
