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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKey;
import org.jwcarman.who.apikey.ApiKeyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** People make, list and revoke their API keys with a token; machines call with the keys. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeysTest extends AbstractApiTest {

  private static final String KEY_HEADER = "X-API-Key";

  @Autowired ApiKeyService apiKeys;

  private ResultActions create(String bearerToken, String body) throws Exception {
    return mockMvc.perform(
        post("/api/keys")
            .header(HttpHeaders.AUTHORIZATION, bearer(bearerToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** Makes a key as {@code subject} and answers its raw key and id. */
  private String[] newKey(String subject, String scopes) throws Exception {
    String json =
        create(token(subject), "{\"name\":\"CI\",\"lifetimeDays\":30,\"scopes\":[" + scopes + "]}")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new String[] {JsonPath.read(json, "$.key"), JsonPath.read(json, "$.id")};
  }

  private UUID identityOf(String subject) throws Exception {
    String json =
        mockMvc
            .perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token(subject))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(json, "$.identityId"));
  }

  @Test
  void a_created_key_calls_with_its_scopes() throws Exception {
    String key = newKey(BOB, "\"task.read\",\"task.create\"")[0];

    mockMvc.perform(get("/api/tasks").header(KEY_HEADER, key)).andExpect(status().isOk());
    mockMvc
        .perform(delete("/api/tasks/{id}", UUID.randomUUID()).header(KEY_HEADER, key))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/me").header(KEY_HEADER, key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signedInWith").value("api-key"));
  }

  @Test
  void a_key_holds_only_its_scopes_not_everything_its_owner_can_do() throws Exception {
    // alice may delete tasks; this key may only read them
    String key = newKey(ALICE, "\"task.read\"")[0];

    mockMvc.perform(get("/api/tasks").header(KEY_HEADER, key)).andExpect(status().isOk());
    mockMvc
        .perform(delete("/api/tasks/{id}", UUID.randomUUID()).header(KEY_HEADER, key))
        .andExpect(status().isForbidden());
  }

  @Test
  void the_key_is_shown_once() throws Exception {
    newKey(BOB, "\"task.read\"");

    mockMvc
        .perform(get("/api/keys").header(HttpHeaders.AUTHORIZATION, bearer(token(BOB))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("CI"))
        .andExpect(jsonPath("$[0].expiresAt").exists())
        .andExpect(jsonPath("$[0].key").doesNotExist());
  }

  @Test
  void a_key_cannot_create_a_key() throws Exception {
    String key = newKey(ALICE, "\"task.read\"")[0];

    mockMvc
        .perform(
            post("/api/keys")
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Forever\",\"lifetimeDays\":365,\"scopes\":[\"task.read\"]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void a_key_cannot_revoke_a_key() throws Exception {
    String[] key = newKey(ALICE, "\"task.read\"");

    mockMvc
        .perform(delete("/api/keys/{id}", key[1]).header(KEY_HEADER, key[0]))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/tasks").header(KEY_HEADER, key[0])).andExpect(status().isOk());
  }

  @Test
  void revoking_another_identitys_key_is_404_and_the_key_still_works() throws Exception {
    String[] bobs = newKey(BOB, "\"task.read\"");

    mockMvc
        .perform(
            delete("/api/keys/{id}", bobs[1])
                .header(HttpHeaders.AUTHORIZATION, bearer(token(ALICE))))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/tasks").header(KEY_HEADER, bobs[0])).andExpect(status().isOk());
  }

  @Test
  void a_revoked_key_is_401() throws Exception {
    String[] key = newKey(BOB, "\"task.read\"");

    mockMvc
        .perform(
            delete("/api/keys/{id}", key[1]).header(HttpHeaders.AUTHORIZATION, bearer(token(BOB))))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/tasks").header(KEY_HEADER, key[0]))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_blank_name_is_400_and_creates_nothing() throws Exception {
    UUID bob = identityOf(BOB);
    List<ApiKey> before = apiKeys.findByIdentity(bob);

    create(token(BOB), "{\"name\":\"  \",\"lifetimeDays\":30,\"scopes\":[\"task.read\"]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    create(token(BOB), "{\"lifetimeDays\":30,\"scopes\":[\"task.read\"]}")
        .andExpect(status().isBadRequest());

    assertThat(apiKeys.findByIdentity(bob)).hasSameSizeAs(before);
  }

  @Test
  void a_name_too_long_to_store_is_a_400_problem() throws Exception {
    create(
            token(BOB),
            "{\"name\":\"" + "n".repeat(256) + "\",\"lifetimeDays\":30,\"scopes\":[\"task.read\"]}")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(containsString("name")));
  }

  @Test
  void a_malformed_body_is_a_400_problem() throws Exception {
    create(token(BOB), "{\"name\":\"CI\",\"lifetimeDays\":\"thirty\"")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void a_scope_the_caller_lacks_is_400() throws Exception {
    // bob is a member: he may not delete tasks, so no key of his may either
    create(token(BOB), "{\"name\":\"CI\",\"lifetimeDays\":30,\"scopes\":[\"task.delete\"]}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("task.delete")));
  }

  @Test
  void a_missing_lifetime_or_one_over_the_maximum_is_400() throws Exception {
    create(token(BOB), "{\"name\":\"CI\",\"scopes\":[\"task.read\"]}")
        .andExpect(status().isBadRequest());
    create(token(BOB), "{\"name\":\"CI\",\"lifetimeDays\":366,\"scopes\":[\"task.read\"]}")
        .andExpect(status().isBadRequest());
  }
}
