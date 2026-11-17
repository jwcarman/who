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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.Browser;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /who/me/email-addresses} and {@code DELETE /who/me/email-addresses/{id}} through who's
 * autoconfiguration on a browser chain and PostgreSQL. The application is {@link
 * EmailAddressChangeTest}'s, so its context is shared.
 */
@SpringBootTest(
    classes = EmailAddressChangeTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=" + EmailLinkSignInTest.LINK_BASE_URL,
      "who.email-link.address-confirmation-url=" + EmailAddressChangeTest.PROFILE_URL,
      "who.email-link.code-secret=" + EmailLinkSignInTest.CODE_SECRET,
      "who.passkey.enabled=false"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoEmailAddressesEndpointsTest {

  private static final String ALICE = "alice@example.com";
  private static final String ALICE_TOO = "alice.too@example.com";
  private static final String BOB = "bob@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MutableClock clock;
  @Autowired private EmailAddressChangeTest.Outbox outbox;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService credentials;

  private final JsonMapper json = JsonMapper.builder().build();
  private MockMvc mvc;
  private Identity alice;
  private Identity bob;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_email_address_request").update();
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    clock.set(EmailLinkSignInTest.NOW);
    outbox.sent.clear();
    alice = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    bob = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** A browser signed in by an email link to the address, as a person would be. */
  private Browser signedIn(String address) throws Exception {
    Browser browser = new Browser(mvc);
    assertThat(
            browser
                .performWithCsrf(
                    post("/who/email/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(202);
    String link = outbox.of(EmailLinkMessage.class).getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(
            browser
                .performWithCsrf(
                    post("/who/email/sign-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    return browser;
  }

  private JsonNode list(Browser browser) throws Exception {
    MvcResult listed = browser.perform(get("/who/me/email-addresses"));
    assertThat(listed.getResponse().getStatus()).isEqualTo(200);
    return json.readTree(listed.getResponse().getContentAsString());
  }

  private int deleteStatus(Browser browser, String id) throws Exception {
    return browser
        .performWithCsrf(delete("/who/me/email-addresses/" + id))
        .getResponse()
        .getStatus();
  }

  @Nested
  class Listing {

    @Test
    void lists_the_callers_addresses_and_only_theirs() throws Exception {
      EmailCredential first = credentials.register(alice, ALICE);
      credentials.register(alice, ALICE_TOO);
      credentials.register(bob, BOB);
      Browser browser = signedIn(ALICE);

      JsonNode listed = list(browser);

      assertThat(listed).hasSize(2);
      assertThat(listed.get(0).path("id").asString()).isEqualTo(first.id().toString());
      assertThat(listed.get(0).path("address").asString()).isEqualTo(ALICE);
      assertThat(listed.get(1).path("address").asString()).isEqualTo(ALICE_TOO);
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(new Browser(mvc).perform(get("/who/me/email-addresses")).getResponse().getStatus())
          .isEqualTo(401);
    }
  }

  @Nested
  class Removing {

    @Test
    void the_listed_id_is_the_one_delete_accepts() throws Exception {
      credentials.register(alice, ALICE);
      credentials.register(alice, ALICE_TOO);
      Browser browser = signedIn(ALICE);
      String listed = list(browser).get(1).path("id").asString();

      assertThat(deleteStatus(browser, listed)).isEqualTo(204);

      assertThat(credentials.findAllFor(alice.id()))
          .extracting(EmailCredential::address)
          .containsExactly(ALICE);
    }

    @Test
    void the_last_address_is_conflict() throws Exception {
      EmailCredential only = credentials.register(alice, ALICE);
      Browser browser = signedIn(ALICE);

      MvcResult refused = browser.performWithCsrf(delete("/who/me/email-addresses/" + only.id()));
      assertThat(refused.getResponse().getStatus()).isEqualTo(409);
      assertThat(refused.getResponse().getHeader("Who-Error")).isEqualTo("last-credential");

      assertThat(credentials.findById(only.id())).isPresent();
    }

    @Test
    void cannot_delete_another_identitys_address() throws Exception {
      credentials.register(alice, ALICE);
      credentials.register(bob, BOB);
      EmailCredential theirs = credentials.register(bob, "bob.too@example.com");
      Browser browser = signedIn(ALICE);

      assertThat(deleteStatus(browser, theirs.id().toString())).isEqualTo(404);

      assertThat(credentials.findById(theirs.id())).isPresent();
    }
  }
}
