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
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.RecordingSender;
import org.jwcarman.who.autoconfigure.PasskeySignInTest.Browser;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.passkey.PasskeyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /who/me/passkeys} and {@code DELETE /who/me/passkeys/{credentialId}} through who's
 * autoconfiguration, with real WebAuthn ceremonies. The application is {@link PasskeySignInTest}'s,
 * so its context is shared.
 */
@SpringBootTest(
    classes = PasskeySignInTest.App.class,
    properties = {
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=app.example.com"
    })
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeyCredentialsEndpointsTest {

  private static final String ORIGIN = PasskeySignInTest.ORIGIN;
  private static final String OWNER = PasskeySignInTest.OWNER;
  private static final String OTHER = PasskeySignInTest.OTHER;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    WhoTestPostgres.registerFreshDatabase(registry);
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MutableClock clock;
  @Autowired private RecordingSender sender;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private PasskeyService passkeyService;

  private final JsonMapper json = JsonMapper.builder().build();
  private MockMvc mvc;
  private Identity owner;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_passkey_credential").update();
    jdbcClient.sql("DELETE FROM who_passkey_user").update();
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    clock.set(PasskeySignInTest.NOW);
    sender.sent.clear();
    owner = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    emailCredentialService.register(owner, OWNER);
    Identity other = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    emailCredentialService.register(other, OTHER);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder request) {
    return request.contentType(MediaType.APPLICATION_JSON);
  }

  private Browser signedInByEmail(String address) throws Exception {
    Browser browser = new Browser(mvc);
    int before = sender.sent.size();
    MvcResult generated =
        browser.performWithCsrf(
            jsonBody(post("/who/email/send")).content("{\"email\":\"" + address + "\"}"));
    assertThat(generated.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(before + 1);
    MvcResult confirmed =
        browser.performWithCsrf(
            jsonBody(post("/who/email/sign-in"))
                .content("{\"token\":\"" + sender.sent.getLast().token() + "\"}"));
    assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
    return browser;
  }

  private void registerPasskey(Browser browser, PasskeyDevice device, String label)
      throws Exception {
    MvcResult options = browser.performWithCsrf(post("/who/me/passkeys/options"));
    assertThat(options.getResponse().getStatus()).isEqualTo(200);
    MvcResult registered =
        browser.performWithCsrf(
            jsonBody(post("/who/me/passkeys"))
                .content(device.register(options.getResponse().getContentAsString(), label)));
    assertThat(registered.getResponse().getStatus()).isEqualTo(200);
  }

  private JsonNode list(Browser browser) throws Exception {
    MvcResult listed = browser.perform(get("/who/me/passkeys"));
    assertThat(listed.getResponse().getStatus()).isEqualTo(200);
    return json.readTree(listed.getResponse().getContentAsString());
  }

  private int deleteStatus(Browser browser, String credentialId) throws Exception {
    return browser
        .performWithCsrf(delete("/who/me/passkeys/" + credentialId))
        .getResponse()
        .getStatus();
  }

  private boolean canSignIn(PasskeyDevice device) throws Exception {
    Browser browser = new Browser(mvc);
    MvcResult options = browser.performWithCsrf(post("/who/passkey/sign-in/options"));
    MvcResult signedIn =
        browser.performWithCsrf(
            jsonBody(post("/who/passkey/sign-in"))
                .content(device.signIn(options.getResponse().getContentAsString())));
    return signedIn.getResponse().getStatus() == 200;
  }

  @Nested
  class Listing {

    @Test
    void a_caller_with_no_passkeys_gets_an_empty_list() throws Exception {
      Browser browser = signedInByEmail(OWNER);

      MvcResult listed = browser.perform(get("/who/me/passkeys"));

      assertThat(listed.getResponse().getStatus()).isEqualTo(200);
      assertThat(listed.getResponse().getContentAsString()).isEqualTo("[]");
    }

    @Test
    void lists_a_registered_passkey_by_its_credential_id() throws Exception {
      Browser browser = signedInByEmail(OWNER);
      PasskeyDevice device = new PasskeyDevice(ORIGIN);
      registerPasskey(browser, device, "Laptop");

      JsonNode listed = list(browser);

      assertThat(listed).hasSize(1);
      assertThat(listed.get(0).path("credentialId").asString()).isEqualTo(device.credentialId());
      assertThat(listed.get(0).path("label").asString()).isEqualTo("Laptop");
      assertThat(listed.get(0).has("id")).isFalse();
    }
  }

  @Nested
  class Removing {

    @Test
    void the_listed_credential_id_is_the_one_delete_accepts() throws Exception {
      Browser browser = signedInByEmail(OWNER);
      PasskeyDevice device = new PasskeyDevice(ORIGIN);
      registerPasskey(browser, device, "Laptop");
      String listed = list(browser).get(0).path("credentialId").asString();

      assertThat(deleteStatus(browser, listed)).isEqualTo(204);

      assertThat(passkeyService.list(owner.id())).isEmpty();
      assertThat(canSignIn(device)).isFalse();
    }

    @Test
    void cannot_delete_another_identitys_passkey() throws Exception {
      PasskeyDevice ownerDevice = new PasskeyDevice(ORIGIN);
      registerPasskey(signedInByEmail(OWNER), ownerDevice, "Laptop");
      Browser intruder = signedInByEmail(OTHER);
      // with and without a passkey user entity of its own
      assertThat(deleteStatus(intruder, ownerDevice.credentialId())).isEqualTo(404);
      registerPasskey(intruder, new PasskeyDevice(ORIGIN), "Intruder");

      assertThat(deleteStatus(intruder, ownerDevice.credentialId())).isEqualTo(404);

      assertThat(canSignIn(ownerDevice)).isTrue();
    }

    @Test
    void an_unknown_credential_id_is_not_found_too() throws Exception {
      assertThat(deleteStatus(signedInByEmail(OWNER), "AAAA")).isEqualTo(404);
    }
  }
}
