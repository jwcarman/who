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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.federated.FederatedCredential;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /who/me/accounts} and {@code DELETE /who/me/accounts/{id}} through who's
 * autoconfiguration, a stub provider registered as {@code test} and PostgreSQL.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LinkedAccountsEndpointsTest extends AbstractOidcTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ACCOUNTS = "/who/me/accounts";

  private JsonNode list() throws Exception {
    MvcResult listed = browser.perform(get(ACCOUNTS)).andReturn();
    assertThat(listed.getResponse().getStatus()).isEqualTo(200);
    return JSON.readTree(listed.getResponse().getContentAsString());
  }

  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private LastCredentialGuard guard;

  private int unlink(String id) throws Exception {
    return browser.perform(delete(ACCOUNTS + "/" + id)).andReturn().getResponse().getStatus();
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_accounts_by_the_registration_that_signs_them_in() throws Exception {
      FederatedCredential second =
          federatedCredentialService.link(alice, "https://gone.example.com", "alice-too");
      federatedCredentialService.link(
          identityRepository.save(Identity.create(IdentityStatus.ACTIVE)),
          PROVIDER.issuer(),
          "bob");
      browser.signedInAs(alice.id(), aliceCredential.id());

      JsonNode listed = list();

      assertThat(listed).hasSize(2);
      assertThat(listed.get(0).path("id").asString()).isEqualTo(aliceCredential.id().toString());
      assertThat(listed.get(0).path("provider").asString()).isEqualTo("test");
      assertThat(listed.get(1).path("id").asString()).isEqualTo(second.id().toString());
      assertThat(listed.get(1).path("provider").asString()).isEqualTo("https://gone.example.com");
      assertThat(Instant.parse(listed.get(0).path("linkedAt").asString()))
          .isEqualTo(aliceCredential.linkedAt());
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(browser.perform(get(ACCOUNTS)).andReturn().getResponse().getStatus())
          .isEqualTo(401);
    }
  }

  @Nested
  class Unlinking {

    @Test
    void the_listed_id_is_the_one_delete_accepts() throws Exception {
      FederatedCredential second =
          federatedCredentialService.link(alice, "https://other.example.com", "alice-too");
      browser.signedInAs(alice.id(), aliceCredential.id());
      String listed = list().get(1).path("id").asString();

      assertThat(unlink(listed)).isEqualTo(204);

      assertThat(federatedCredentialRepository.findById(second.id())).isEmpty();
      assertThat(list()).hasSize(1);
    }

    @Test
    void the_last_account_is_conflict() throws Exception {
      browser.signedInAs(alice.id(), aliceCredential.id());

      assertThat(unlink(aliceCredential.id().toString())).isEqualTo(409);

      assertThat(federatedCredentialRepository.findById(aliceCredential.id())).isPresent();
    }

    @Test
    void cannot_unlink_another_identitys_account() throws Exception {
      Identity bob = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
      federatedCredentialService.link(bob, "https://other.example.com", "bob");
      FederatedCredential theirs =
          federatedCredentialService.link(bob, "https://other.example.com", "bob-too");
      browser.signedInAs(alice.id(), aliceCredential.id());

      assertThat(unlink(theirs.id().toString())).isEqualTo(404);

      assertThat(federatedCredentialRepository.findById(theirs.id())).isPresent();
    }

    @Test
    void an_unknown_id_is_not_found() throws Exception {
      browser.signedInAs(alice.id(), aliceCredential.id());

      assertThat(unlink(UUID.randomUUID().toString())).isEqualTo(404);
    }
  }

  /**
   * Only an account a configured provider registration signs in counts toward the last-credential
   * rule: one whose registration was removed, or a JWT issuer and subject, signs no one in.
   */
  @Nested
  class Last_credential_rule {

    private static final String GONE_ISSUER = "https://gone.example.com";
    private static final String JWT_ISSUER = "https://jwt-issuer.example.com";

    private Identity carol;
    private EmailCredential carolEmail;

    private void carolWithAnEmailAndAnAccountAt(String issuer) {
      carol = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
      carolEmail = emailCredentialService.register(carol, "carol@example.com");
      federatedCredentialService.link(carol, issuer, "carol");
      browser.signedInAs(carol.id(), carolEmail.id());
    }

    private int removeEmail() throws Exception {
      return browser
          .perform(delete("/who/me/email-addresses/" + carolEmail.id()))
          .andReturn()
          .getResponse()
          .getStatus();
    }

    @Test
    void an_account_whose_registration_was_removed_does_not_count() throws Exception {
      carolWithAnEmailAndAnAccountAt(GONE_ISSUER);

      assertThat(guard.counts(carol.id())).containsEntry("account", 0L);
      assertThat(removeEmail()).isEqualTo(409);
    }

    @Test
    void a_jwt_enrolled_subject_does_not_count() throws Exception {
      carolWithAnEmailAndAnAccountAt(JWT_ISSUER);

      assertThat(guard.counts(carol.id())).containsEntry("account", 0L);
      assertThat(removeEmail()).isEqualTo(409);
    }

    @Test
    void an_account_a_configured_registration_signs_in_counts() throws Exception {
      carolWithAnEmailAndAnAccountAt(PROVIDER.issuer());

      assertThat(guard.counts(carol.id())).containsEntry("account", 1L);
      assertThat(removeEmail()).isEqualTo(204);
    }

    @Test
    void unlinking_an_account_that_does_not_count_is_never_the_last() throws Exception {
      FederatedCredential gone = federatedCredentialService.link(alice, GONE_ISSUER, "alice");
      browser.signedInAs(alice.id(), aliceCredential.id());

      assertThat(unlink(gone.id().toString())).isEqualTo(204);

      assertThat(federatedCredentialRepository.findById(gone.id())).isEmpty();
    }
  }
}
