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
package org.jwcarman.who.examples.webapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** The example starts, and its pages are guarded. Postgres comes from Testcontainers here. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WebAppApplicationTest {

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
  void a_member_sees_the_board() throws Exception {
    MockHttpSession member = signedInAs("member@example.com", TeamRole.MEMBER);
    tasks.add("Write the example", whoService.createIdentity().id());

    mockMvc
        .perform(get("/").session(member).accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Write the example")));
  }

  @Test
  void a_member_adds_a_task() throws Exception {
    MockHttpSession member = signedInAs("adder@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(post("/tasks").session(member).param("title", "Water the plants").with(csrf()))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/"));

    assertThat(tasks.findAll()).extracting(Task::title).contains("Water the plants");
  }

  @Test
  void a_member_may_not_manage_members() throws Exception {
    MockHttpSession member = signedInAs("not-an-admin@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(get("/members").session(member).accept(MediaType.TEXT_HTML))
        .andExpect(status().isForbidden());
  }

  @Test
  void a_signed_in_member_is_sent_from_sign_in_to_the_board() throws Exception {
    MockHttpSession member = signedInAs("already-in@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(get("/signin").session(member).accept(MediaType.TEXT_HTML))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/"));
  }

  @Test
  void a_signed_in_member_back_from_a_provider_sees_they_are_signed_in() throws Exception {
    MockHttpSession member = signedInAs("linking@example.com", TeamRole.MEMBER);

    mockMvc
        .perform(
            get("/signin").param("error", "signed-in").session(member).accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("You are signed in")))
        .andExpect(content().string(containsString("id=\"sign-out\"")));
  }

  @Test
  void a_stranger_is_sent_to_sign_in() throws Exception {
    mockMvc
        .perform(get("/").accept(MediaType.TEXT_HTML))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", "/signin"));
  }

  @Test
  void the_sign_in_page_is_open() throws Exception {
    mockMvc
        .perform(get("/signin").accept(MediaType.TEXT_HTML))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/oauth2/authorization/keycloak")));
  }

  private MockHttpSession signedInAs(String address, TeamRole role) {
    Identity identity = whoService.createIdentity();
    EmailCredential credential = emailCredentials.register(identity, address);
    rbacService.assignRoleByName(identity, role);
    return WhoTestSessions.signedIn(whoSessions, identity.id(), credential.id());
  }
}
