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
package org.jwcarman.who.examples.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import tools.jackson.databind.json.JsonMapper;

/** The API, keys made on the page, and the trace id every answer names. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiTest extends AbstractObservabilityTest {

  private static final JsonMapper JSON = new JsonMapper();

  @Test
  void a_response_names_its_trace() throws Exception {
    String traceId =
        mockMvc.perform(get("/api/greeting")).andReturn().getResponse().getHeader("X-Trace-Id");

    assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
  }

  @Test
  void a_key_from_the_page_reads_and_cannot_write() throws Exception {
    String key = (String) makeKey(signedIn()).get("key");

    mockMvc.perform(get("/api/greeting").header("X-API-Key", key)).andExpect(status().isOk());
    mockMvc
        .perform(post("/api/greeting").header("X-API-Key", key))
        .andExpect(status().isForbidden());
  }

  @Test
  void an_unknown_key_is_401() throws Exception {
    mockMvc
        .perform(get("/api/greeting").header("X-API-Key", "who_unknown"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_revoked_key_is_401() throws Exception {
    MockHttpSession session = signedIn();
    Map<?, ?> key = makeKey(session);

    mockMvc
        .perform(delete("/keys/" + key.get("id")).session(session).with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/greeting").header("X-API-Key", (String) key.get("key")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void revoking_a_key_that_is_not_yours_is_404() throws Exception {
    Map<?, ?> key = makeKey(signedIn());

    mockMvc
        .perform(delete("/keys/" + key.get("id")).session(signedIn()).with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(delete("/keys/" + UUID.randomUUID()).session(signedIn()).with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  void making_a_key_needs_a_recent_sign_in() throws Exception {
    MockHttpSession session = signedInAt(Instant.now().minus(Duration.ofMinutes(11)));

    mockMvc
        .perform(post("/keys").session(session).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void making_a_key_signed_out_is_401() throws Exception {
    mockMvc.perform(post("/keys").with(csrf())).andExpect(status().isUnauthorized());
  }

  private Map<?, ?> makeKey(MockHttpSession session) throws Exception {
    String body =
        mockMvc
            .perform(post("/keys").session(session).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readValue(body, Map.class);
  }
}
