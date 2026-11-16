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
package org.jwcarman.who.examples.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** People call the API with access tokens for accounts the app linked. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiApplicationTest extends AbstractApiTest {

  @Autowired Setup setup;
  @Autowired FederatedCredentialRepository federatedCredentials;

  @Test
  void a_linked_subjects_token_reads_the_board() throws Exception {
    mockMvc
        .perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer(token(ALICE))))
        .andExpect(status().isOk());
  }

  @Test
  void me_says_who_and_how() throws Exception {
    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token(ALICE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signedInWith").value("token"))
        .andExpect(
            jsonPath("$.permissions")
                .value(containsInAnyOrder(TeamRole.ADMIN.permissions().toArray(String[]::new))));
  }

  @Test
  void an_unlinked_subjects_token_is_401() throws Exception {
    mockMvc
        .perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer(token(CAROL))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_token_for_another_audience_is_401() throws Exception {
    String token =
        token(SIGNING_KEY, ISSUER, ALICE, "some-other-api", Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void an_expired_token_is_401() throws Exception {
    String token = token(SIGNING_KEY, ISSUER, ALICE, AUDIENCE, Instant.now().minusSeconds(120));

    mockMvc
        .perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_token_signed_by_another_key_is_401() throws Exception {
    String token = token(rsa(), ISSUER, ALICE, AUDIENCE, Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_token_from_another_issuer_is_401() throws Exception {
    String token =
        token(
            SIGNING_KEY,
            "https://elsewhere.example",
            ALICE,
            AUDIENCE,
            Instant.now().plusSeconds(60));

    mockMvc
        .perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void no_token_is_401_with_a_bearer_challenge() throws Exception {
    mockMvc
        .perform(get("/api/tasks"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void a_member_cannot_delete_a_task() throws Exception {
    mockMvc
        .perform(
            delete("/api/tasks/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(token(BOB))))
        .andExpect(status().isForbidden())
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void setup_is_idempotent() throws Exception {
    setup.run(null);
    setup.run(null);

    assertThat(federatedCredentials.findByIssuerAndSubject(ISSUER, ALICE)).isPresent();
    assertThat(federatedCredentials.findByIssuerAndSubject(ISSUER, BOB)).isPresent();
    mockMvc
        .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token(ALICE))))
        .andExpect(status().isOk());
  }
}
