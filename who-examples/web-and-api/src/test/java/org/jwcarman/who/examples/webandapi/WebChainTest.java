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
package org.jwcarman.who.examples.webandapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKey;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.NewApiKey;
import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The website: its pages need a session, and people make, list and revoke their API keys there,
 * which machines then use on the API.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WebChainTest extends AbstractWebAndApiTest {

  private static final String KEY_HEADER = "X-API-Key";

  @Autowired ApiKeyService apiKeys;

  @Test
  void the_sign_in_page_is_open() throws Exception {
    mockMvc.perform(get("/signin").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
  }

  @Test
  void a_session_sees_the_board() throws Exception {
    mockMvc
        .perform(get("/").session(signedInAs(TeamRole.MEMBER)).accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk())
        .andExpect(model().attributeExists("tasks"));
  }

  @Test
  void a_stranger_is_sent_to_sign_in() throws Exception {
    mockMvc
        .perform(get("/").accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string(HttpHeaders.LOCATION, "/signin"));
  }

  @Test
  void a_key_on_a_web_page_signs_nothing_in() throws Exception {
    String key = makeKey(person(TeamRole.MEMBER), "task.read").key();

    mockMvc
        .perform(get("/").header(KEY_HEADER, key).accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string(HttpHeaders.LOCATION, "/signin"));
  }

  @Test
  void a_token_on_a_web_page_signs_nothing_in() throws Exception {
    mockMvc
        .perform(
            get("/")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(ALICE)))
                .accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string(HttpHeaders.LOCATION, "/signin"));
  }

  @Test
  void a_key_made_on_the_keys_page_reads_the_api() throws Exception {
    NewApiKey key = makeKey(person(TeamRole.MEMBER), "task.read");

    mockMvc.perform(get("/api/tasks").header(KEY_HEADER, key.key())).andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/tasks")
                .header(KEY_HEADER, key.key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"From a key\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void the_keys_page_lists_the_persons_keys() throws Exception {
    Person bob = person(TeamRole.MEMBER);
    NewApiKey key = makeKey(bob, "task.read");

    Object keys =
        mockMvc
            .perform(get("/keys").session(bob.session()).accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk())
            .andReturn()
            .getModelAndView()
            .getModel()
            .get("keys");

    assertThat(keys)
        .asInstanceOf(InstanceOfAssertFactories.list(ApiKey.class))
        .extracting(ApiKey::id)
        .containsExactly(key.id());
  }

  @Test
  void a_keys_post_without_csrf_is_403() throws Exception {
    Person bob = person(TeamRole.MEMBER);

    mockMvc
        .perform(
            post("/keys")
                .session(bob.session())
                .param("name", "CI")
                .param("lifetimeDays", "30")
                .param("scopes", "task.read"))
        .andExpect(status().isForbidden());

    assertThat(apiKeys.findByIdentity(bob.identity().id())).isEmpty();
  }

  @Test
  void a_scope_the_person_lacks_is_refused_on_the_page() throws Exception {
    Person bob = person(TeamRole.MEMBER);

    mockMvc
        .perform(
            post("/keys")
                .session(bob.session())
                .with(csrf())
                .param("name", "CI")
                .param("lifetimeDays", "30")
                .param("scopes", "task.delete"))
        .andExpect(status().isOk())
        .andExpect(model().attributeExists("error"))
        .andExpect(model().attributeDoesNotExist("newKey"));

    assertThat(apiKeys.findByIdentity(bob.identity().id())).isEmpty();
  }

  @Test
  void revoking_another_persons_key_is_404() throws Exception {
    NewApiKey bobs = makeKey(person(TeamRole.MEMBER), "task.read");

    mockMvc
        .perform(
            post("/keys/{id}/revoke", bobs.id())
                .session(person(TeamRole.ADMIN).session())
                .with(csrf()))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/tasks").header(KEY_HEADER, bobs.key())).andExpect(status().isOk());
  }

  @Test
  void the_owner_revokes_a_key() throws Exception {
    Person bob = person(TeamRole.MEMBER);
    NewApiKey key = makeKey(bob, "task.read");

    mockMvc
        .perform(post("/keys/{id}/revoke", key.id()).session(bob.session()).with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string(HttpHeaders.LOCATION, "/keys"));

    mockMvc
        .perform(get("/api/tasks").header(KEY_HEADER, key.key()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_key_reads_only_itself() throws Exception {
    NewApiKey key = makeKey(person(TeamRole.MEMBER), "task.read");

    mockMvc
        .perform(get("/api/keys/self").header(KEY_HEADER, key.key()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(key.id().toString()))
        .andExpect(jsonPath("$.key").doesNotExist());
    mockMvc
        .perform(get("/api/keys/self").header(HttpHeaders.AUTHORIZATION, bearer(token(ALICE))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  /** Makes a key on the Keys page, as {@code person}, and returns it as the page showed it. */
  private NewApiKey makeKey(Person person, String scope) throws Exception {
    Object shown =
        mockMvc
            .perform(
                post("/keys")
                    .session(person.session())
                    .with(csrf())
                    .param("name", "CI")
                    .param("lifetimeDays", "30")
                    .param("scopes", scope))
            .andExpect(status().isOk())
            .andReturn()
            .getModelAndView()
            .getModel()
            .get("newKey");
    assertThat(shown).isInstanceOf(NewApiKey.class);
    return (NewApiKey) shown;
  }

  @Test
  void making_a_key_needs_a_recent_sign_in() throws Exception {
    Person bob = person(TeamRole.MEMBER, Instant.now().minus(Duration.ofMinutes(11)));

    mockMvc
        .perform(
            post("/keys")
                .session(bob.session())
                .with(csrf())
                .accept(MediaType.TEXT_HTML)
                .param("name", "CI")
                .param("lifetimeDays", "30")
                .param("scopes", "task.read"))
        .andExpect(status().isSeeOther())
        .andExpect(header().string(HttpHeaders.LOCATION, "/signin?again=/keys"));
    mockMvc
        .perform(
            post("/keys")
                .session(bob.session())
                .with(csrf())
                .param("name", "CI")
                .param("lifetimeDays", "30")
                .param("scopes", "task.read"))
        .andExpect(status().isUnauthorized());

    assertThat(apiKeys.findByIdentity(bob.identity().id())).isEmpty();
    mockMvc
        .perform(get("/keys").session(bob.session()).accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk());
  }

  @Test
  void signing_in_again_shows_the_sign_in_page_to_someone_signed_in() throws Exception {
    Person bob = person(TeamRole.MEMBER);

    mockMvc
        .perform(get("/signin").session(bob.session()).accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection());
    mockMvc
        .perform(
            get("/signin")
                .param("again", "/keys")
                .session(bob.session())
                .accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk())
        .andExpect(model().attribute("again", true));
  }

  @Test
  void the_keys_page_lists_sessions() throws Exception {
    Person bob = person(TeamRole.MEMBER);

    mockMvc
        .perform(get("/keys").session(bob.session()).accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"sessions\"")))
        .andExpect(content().string(containsString("/js/sessions.js")));
    mockMvc
        .perform(get("/who/sessions").session(bob.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].current").value(true));
    mockMvc.perform(get("/js/sessions.js")).andExpect(status().isOk());
  }

  @Test
  void ending_another_session_needs_a_recent_sign_in() throws Exception {
    Person bob = person(TeamRole.MEMBER, Instant.now().minus(Duration.ofHours(1)));
    UUID other =
        whoSessions
            .start(bob.identity().id(), UUID.randomUUID(), SignInMethod.EMAIL_CODE, "curl/8.0")
            .id();

    mockMvc
        .perform(delete("/who/sessions/{id}", other).session(bob.session()).with(csrf()))
        .andExpect(status().isUnauthorized());
    assertThat(whoSessions.findLive(other)).isPresent();

    Person fresh = person(TeamRole.MEMBER);
    UUID freshOther =
        whoSessions
            .start(fresh.identity().id(), UUID.randomUUID(), SignInMethod.EMAIL_CODE, "curl/8.0")
            .id();
    mockMvc
        .perform(delete("/who/sessions/{id}", freshOther).session(fresh.session()).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(whoSessions.findLive(freshOther)).isEmpty();
  }
}
