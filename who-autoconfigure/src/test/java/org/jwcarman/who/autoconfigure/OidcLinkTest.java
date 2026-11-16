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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.oidc.PendingSignUp;
import org.jwcarman.who.oidc.PendingSignUps;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.web.servlet.MvcResult;

/** Linking starts only from {@code POST /who/oidc/link/{registrationId}}. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcLinkTest extends AbstractOidcTest {

  @Nested
  class Starting_a_link {

    @Test
    void asks_the_provider_to_sign_the_person_in_again() throws Exception {
      signedInIdentity("https://other.example.com", "bob");

      String authorize = startLink();

      assertThat(authorize).contains("prompt=login").doesNotContain("max_age");
    }

    @Test
    void long_after_signing_in_requires_signing_in_again() throws Exception {
      Identity bob = identityRepository.save(Identity.create());
      FederatedCredential bobs =
          federatedCredentialService.link(bob, "https://other.example.com", "bob");
      browser.signedInAs(bob.id(), bobs.id(), Instant.now().minus(LINK_WINDOW).minusSeconds(60));

      MvcResult result = browser.perform(post("/who/oidc/link/test")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void signed_out_is_401() throws Exception {
      MvcResult result = browser.perform(post("/who/oidc/link/test")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void without_csrf_is_403() throws Exception {
      signedInIdentity("https://other.example.com", "bob");

      MvcResult result = browser.performWithoutCsrf(post("/who/oidc/link/test")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(403);
      assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @Test
    void a_registration_who_does_not_sign_in_with_is_404() throws Exception {
      signedInIdentity("https://other.example.com", "bob");

      MvcResult result = browser.perform(post("/who/oidc/link/unknown")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(404);
      assertThat(result.getResponse().getRedirectedUrl()).isNull();
      assertThat(result.getResponse().getContentAsString()).contains("from the advice");
    }
  }

  @Nested
  class Coming_back {

    @Test
    void an_unknown_account_waits_to_be_linked_to_the_identity_that_asked() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");

      MvcResult result = linkAtProvider("carol", Map.of());

      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(LINK_URL + "?pending=link");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE))
          .isInstanceOfSatisfying(
              PendingSignUp.class, p -> assertThat(p.createdFor()).isEqualTo(bob.id()));
    }

    @Test
    void confirming_links_the_account() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      linkAtProvider("carol", Map.of());

      MvcResult result = browser.perform(post("/who/oidc/link")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      FederatedCredential carol =
          federatedCredentialRepository
              .findByIssuerAndSubject(PROVIDER.issuer(), "carol")
              .orElseThrow();
      assertThat(whoService.resolve(carol))
          .hasValueSatisfying(p -> assertThat(p.identity().id()).isEqualTo(bob.id()));
    }

    @Test
    void a_login_the_provider_only_remembered_still_waits_to_be_confirmed() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      long anHourAgo = Instant.now().minusSeconds(3600).getEpochSecond();

      // prompt=login is a hint; who's check is its own link window, not the provider's auth_time
      MvcResult result = linkAtProvider("carol", Map.of("auth_time", anHourAgo));

      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(LINK_URL + "?pending=link");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNotNull();
    }

    @Test
    void for_someone_else_now_signed_in_links_nothing() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      PROVIDER.nextUser("carol", Map.of());
      String authorize = startLink();
      Identity dave = signedInIdentityKeepingSession("https://other.example.com", "dave");

      MvcResult result = finishAtProvider(authorize);

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
      assertThat(sessionAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
          .isInstanceOfSatisfying(
              WhoSession.class, w -> assertThat(w.identityId()).isEqualTo(dave.id()));
    }

    @Test
    void signed_out_by_the_time_the_provider_answers_links_nothing() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      PROVIDER.nextUser("carol", Map.of());
      String authorize = startLink();
      browser.session().removeAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);

      MvcResult result = finishAtProvider(authorize);

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(events.stream(SignInFailed.class))
          .singleElement()
          .satisfies(e -> assertThat(e.identityId()).isEqualTo(bob.id()));
    }

    @Test
    void a_link_started_too_long_ago_links_nothing() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      PROVIDER.nextUser("carol", Map.of());
      String authorize = startLink();
      ageSavedLink(LINK_WINDOW.plusMinutes(1));

      MvcResult result = finishAtProvider(authorize);

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(events.stream(SignInFailed.class))
          .singleElement()
          .satisfies(
              e -> {
                assertThat(e.reason()).isEqualTo(SignInFailureReason.LINK_REFUSED);
                assertThat(e.identityId()).isEqualTo(bob.id());
              });
      assertThat(me()).isEqualTo(200);
    }

    @Test
    void an_abandoned_link_finished_after_a_newer_sign_in_started_links_nothing() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      PROVIDER.nextUser("carol", Map.of());
      String abandoned = startLink();
      startAuthorization();

      MvcResult result = finishAtProvider(abandoned);

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
    }

    @Test
    void a_callback_cannot_be_replayed() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      PROVIDER.nextUser("carol", Map.of());
      String callback = callbackFrom(startLink());
      browser.perform(get(URI.create(callback)));

      MvcResult replay = browser.perform(get(URI.create(callback))).andReturn();

      assertThat(replay.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signin-failed");
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void an_identity_deactivated_meanwhile_links_nothing() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      PROVIDER.nextUser("carol", Map.of());
      String authorize = startLink();
      whoService.deactivate(bob.id());

      MvcResult result = finishAtProvider(authorize);

      assertThat(result.getResponse().getRedirectedUrl()).startsWith(FAILURE_URL + "?error=");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(federatedCredentialRepository.findByIssuerAndSubject(PROVIDER.issuer(), "carol"))
          .isEmpty();
    }

    @Test
    void an_account_whose_identity_is_inactive_is_already_linked() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      Identity erin = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
      federatedCredentialService.link(erin, PROVIDER.issuer(), "erin");
      whoService.deactivate(erin.id());

      MvcResult result = linkAtProvider("erin", Map.of());

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(LINK_URL + "?error=already-linked");
      assertThat(events.stream(SignInFailed.class)).isEmpty();
      assertThat(me()).isEqualTo(200);
    }

    @Test
    void through_a_plain_oauth2_provider_links_after_confirming() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      PLAIN.nextUser("4242", Map.of("login", "carol"));
      String authorize = startLink("plain", PLAIN);

      MvcResult result = finishAtProvider(authorize);
      browser.perform(post("/who/oidc/link"));

      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(LINK_URL + "?pending=link");
      assertThat(authorize).doesNotContain("prompt=");
      FederatedCredential carol =
          federatedCredentialRepository
              .findByIssuerAndSubject(PLAIN.issuer(), "4242")
              .orElseThrow();
      assertThat(whoService.resolve(carol))
          .hasValueSatisfying(p -> assertThat(p.identity().id()).isEqualTo(bob.id()));
    }

    @Test
    void an_account_already_linked_elsewhere_is_already_linked() throws Exception {
      signedInIdentity("https://other.example.com", "bob");

      MvcResult result = linkAtProvider("alice", Map.of());

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(LINK_URL + "?error=already-linked");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
    }

    @Test
    void an_account_already_yours_changes_nothing() throws Exception {
      Identity bob = signedInIdentity("https://other.example.com", "bob");
      federatedCredentialService.link(bob, PROVIDER.issuer(), "bob-here-too");
      Object before = sessionAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);

      MvcResult result = linkAtProvider("bob-here-too", Map.of());

      assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(LINK_URL);
      assertThat(sessionAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
          .isEqualTo(before);
      assertThat(events.stream(SignInSucceeded.class)).isEmpty();
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
    }
  }

  /** Moves the saved link's start back by {@code age}, as if it had been started that long ago. */
  private void ageSavedLink(Duration age) {
    HttpSession session = browser.session();
    for (String name : Collections.list(session.getAttributeNames())) {
      if (session.getAttribute(name) instanceof OAuth2AuthorizationRequest saved) {
        session.setAttribute(
            name,
            OAuth2AuthorizationRequest.from(saved)
                .attributes(
                    a ->
                        a.put(
                            "org.jwcarman.who.oidc.link.started-at",
                            Instant.now().minus(age).toString()))
                .build());
      }
    }
  }

  @Nested
  class A_plain_sign_in_while_signed_in {

    @Test
    void with_an_unknown_account_is_signed_in_and_keeps_nothing() throws Exception {
      signedInIdentity("https://other.example.com", "bob");

      MvcResult result = signInAtProvider("carol", Map.of());

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signed-in");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
      assertThat(me()).isEqualTo(200);
    }

    @Test
    void after_an_abandoned_link_is_still_not_a_link() throws Exception {
      signedInIdentity("https://other.example.com", "bob");
      startLink();

      MvcResult result = signInAtProvider("carol", Map.of());

      assertThat(result.getResponse().getRedirectedUrl())
          .isEqualTo(FAILURE_URL + "?error=signed-in");
      assertThat(sessionAttribute(PendingSignUps.ATTRIBUTE)).isNull();
    }

    @Test
    void asks_the_provider_for_nothing_extra() throws Exception {
      signedInIdentity("https://other.example.com", "bob");

      assertThat(startAuthorization()).doesNotContain("prompt=").doesNotContain("max_age=");
    }
  }
}
