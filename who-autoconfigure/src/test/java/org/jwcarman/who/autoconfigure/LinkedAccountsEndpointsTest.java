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
import org.jwcarman.who.federated.FederatedCredential;
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

  /**
   * An account linked after alice's own, whose id sorts after it: ids made back to back may share a
   * millisecond, so their order would be luck.
   */
  private FederatedCredential laterAccount(String issuer, String subject) {
    FederatedCredential credential =
        federatedCredentialRepository.save(
            new FederatedCredential(
                UUID.fromString("7fffffff-ffff-7000-8000-000000000001"), issuer, subject));
    return federatedCredentialService.linkExisting(alice, credential);
  }

  private JsonNode list() throws Exception {
    MvcResult listed = browser.perform(get(ACCOUNTS)).andReturn();
    assertThat(listed.getResponse().getStatus()).isEqualTo(200);
    return JSON.readTree(listed.getResponse().getContentAsString());
  }

  private int unlink(String id) throws Exception {
    return browser.perform(delete(ACCOUNTS + "/" + id)).andReturn().getResponse().getStatus();
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_accounts_by_the_registration_that_signs_them_in() throws Exception {
      FederatedCredential second = laterAccount("https://gone.example.com", "alice-too");
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
      FederatedCredential second = laterAccount("https://other.example.com", "alice-too");
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
}
