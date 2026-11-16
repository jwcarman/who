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
package org.jwcarman.who.examples.spa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.test.WhoTestSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The backend starts, its API is guarded, and the page shell is open. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SpaApplicationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  @Autowired MockMvc mockMvc;
  @Autowired TaskStore tasks;
  @Autowired WhoService whoService;
  @Autowired WhoSessions whoSessions;
  @Autowired EmailCredentialService emailCredentials;
  @Autowired RbacService rbacService;

  @Test
  void the_app_starts_with_its_first_admin() {
    assertThat(emailCredentials.findByAddress("admin@example.com")).isPresent();
  }

  @Test
  void a_member_gets_the_board() throws Exception {
    MockHttpSession member = signedInAs("member@example.com", TeamRole.MEMBER);
    tasks.add("Write the example", whoService.createIdentity().id());

    mockMvc
        .perform(get("/api/tasks").session(member).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].title").value(hasItem("Write the example")));
  }

  @Test
  void a_member_adds_a_task() throws Exception {
    MockHttpSession member = signedInAs("adder@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(
            post("/api/tasks")
                .session(member)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Water the plants\"}")
                .with(csrf()))
        .andExpect(status().isNoContent());

    assertThat(tasks.findAll()).extracting(Task::title).contains("Water the plants");
  }

  @Test
  void a_member_may_not_manage_members() throws Exception {
    MockHttpSession member = signedInAs("not-an-admin@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(get("/api/members").session(member).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  void a_member_reads_who_they_are() throws Exception {
    MockHttpSession member = signedInAs("me@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(get("/api/me").session(member).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emails[0].address").value("me@example.com"))
        .andExpect(jsonPath("$.permissions").isArray());
  }

  @Test
  void a_stranger_gets_401_from_the_api() throws Exception {
    mockMvc
        .perform(get("/api/tasks").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_stranger_gets_the_page_shell_and_the_providers() throws Exception {
    for (String page : new String[] {"/", "/signin", "/signin/link", "/members", "/profile"}) {
      mockMvc
          .perform(get(page).accept(MediaType.TEXT_HTML))
          .andExpect(status().isOk())
          .andExpect(forwardedUrl("/index.html"));
    }
    mockMvc
        .perform(get("/api/providers").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("keycloak"));
  }

  private MockHttpSession signedInAs(String address, TeamRole role) {
    Identity identity = whoService.createIdentity();
    EmailCredential credential = emailCredentials.register(identity, address);
    rbacService.assignRoleByName(identity, role);
    return WhoTestSessions.signedIn(whoSessions, identity.id(), credential.id());
  }
}
