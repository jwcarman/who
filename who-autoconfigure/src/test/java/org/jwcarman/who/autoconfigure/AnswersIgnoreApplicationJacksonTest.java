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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.admin.WhoAdminPermissions;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.passkey.JdbcWhoPasskeyCredentialRepository;
import org.jwcarman.who.passkey.JdbcWhoPasskeyUserRepository;
import org.jwcarman.who.passkey.TestPasskeys;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.web.SessionNotFoundException;
import org.jwcarman.who.test.StubOidcProvider;
import org.jwcarman.who.test.WhoMockMvcBrowser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An application that sets Jackson its own way (snake_case names, dates as numbers, nulls left out)
 * still gets every who answer in who's shape, which who's TypeScript client reads.
 */
@SpringBootTest(
    classes = AnswersIgnoreApplicationJacksonTest.App.class,
    properties = {
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "who.jwt.enabled=false",
      "who.turnstile.enabled=false",
      "who.oidc.enabled=true",
      "who.oidc.failure-url=/signin",
      "spring.security.oauth2.client.registration.stub.client-id=client",
      "spring.security.oauth2.client.registration.stub.client-secret=secret",
      "spring.security.oauth2.client.registration.stub.scope=openid,email",
      "spring.session.jdbc.initialize-schema=always",
      "spring.jackson.property-naming-strategy=SNAKE_CASE",
      "spring.jackson.datatype.datetime.write-dates-as-timestamps=true",
      "spring.jackson.default-property-inclusion=non_null",
      "spring.jackson.datatype.enum.write-enums-using-index=true"
    })
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnswersIgnoreApplicationJacksonTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final JsonMapper READER = JsonMapper.builder().build();

  static final StubOidcProvider PROVIDER = StubOidcProvider.start();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    WhoTestPostgres.registerFreshDatabase(registry);
    registry.add("spring.security.oauth2.client.provider.stub.issuer-uri", PROVIDER::issuer);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    WhoEmailSender whoEmailSender() {
      return _ -> {};
    }

    @Bean
    CatchAllAdvice catchAllAdvice() {
      return new CatchAllAdvice();
    }

    @Bean
    Orders orders() {
      return new Orders();
    }
  }

  /** An application advice that answers everything its own way. */
  @RestControllerAdvice
  static class CatchAllAdvice {
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
    Map<String, String> anything(Exception e) {
      return Map.of("app", e.getClass().getSimpleName());
    }
  }

  /** An application endpoint that fails with one of who's exceptions. */
  @RestController
  static class Orders {
    @GetMapping("/app/orders/{id}")
    String order(@PathVariable("id") String id) {
      throw new SessionNotFoundException();
    }

    @GetMapping("/app/sample")
    Sample sample() {
      return new Sample("value", null, NOW);
    }
  }

  /** An application answer, which follows the application's settings. */
  record Sample(String someName, String nothing, Instant at) {}

  @Autowired private MockMvc mvc;
  @Autowired private IdentityRepository identities;
  @Autowired private EmailCredentialService emailCredentials;
  @Autowired private JdbcWhoPasskeyUserRepository passkeyUsers;
  @Autowired private JdbcWhoPasskeyCredentialRepository passkeyCredentials;
  @Autowired private JdbcClient jdbc;
  @Autowired private FederatedCredentialService federatedCredentials;
  @Autowired private RbacService rbac;

  private Identity identity;
  private WhoMockMvcBrowser browser;

  @BeforeEach
  void signIn() {
    identity = identities.save(Identity.create(IdentityStatus.ACTIVE));
    UUID credentialId =
        emailCredentials.register(identity, "jackson-" + identity.id() + "@example.com").id();
    browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId, NOW);
  }

  private static JsonNode body(MvcResult result) throws Exception {
    return READER.readTree(result.getResponse().getContentAsString());
  }

  @Nested
  class TheApplicationsOwnAnswers {

    @Test
    void follow_its_settings() throws Exception {
      MvcResult result = browser.perform(get("/app/sample")).andReturn();

      assertThat(body(result).propertyNames()).containsExactlyInAnyOrder("some_name", "at");
      assertThat(body(result).get("at").isNumber()).isTrue();
    }
  }

  @Nested
  class Me {

    @Test
    void the_answer_keeps_whos_names_and_formats() throws Exception {
      MvcResult result = browser.perform(get("/who/me")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode me = body(result);
      assertThat(me.propertyNames())
          .containsExactlyInAnyOrder(
              "identityId", "permissions", "signInMethod", "recentSignInUntil", "credentials");
      assertThat(me.get("identityId").asString()).isEqualTo(identity.id().toString());
      assertThat(me.get("recentSignInUntil").isString()).isTrue();
    }

    @Test
    void a_signed_out_request_is_not_signed_in() throws Exception {
      MvcResult result = mvc.perform(get("/who/me")).andReturn();

      // who's entry point answers it, outside Spring MVC, through the application's advice
      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
  }

  @Nested
  class Sessions {

    @Test
    void the_listing_keeps_whos_names() throws Exception {
      MvcResult result = browser.perform(get("/who/me/sessions")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode first = body(result).get(0);
      assertThat(first.propertyNames())
          .containsExactlyInAnyOrder(
              "id", "device", "userAgent", "signInMethod", "signedInAt", "lastSeenAt", "current");
      assertThat(first.get("current").asBoolean()).isTrue();
    }

    @Test
    void a_controller_error_names_itself_in_the_who_error_header() throws Exception {
      MvcResult result =
          browser.perform(delete("/who/me/sessions/" + UUID.randomUUID())).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(404);
      assertThat(result.getResponse().getHeader("Who-Error")).isEqualTo("session-not-found");
    }
  }

  @Nested
  class EmailAddresses {

    @Test
    void the_list_keeps_whos_names() throws Exception {
      MvcResult result = browser.perform(get("/who/me/email-addresses")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode first = body(result).get(0);
      assertThat(first.propertyNames()).containsExactlyInAnyOrder("id", "address", "createdAt");
      assertThat(first.get("createdAt").isString()).isTrue();
    }
  }

  @Nested
  class Passkeys {

    @Test
    void a_passkey_never_used_has_a_null_last_used_at() throws Exception {
      TestPasskeys.registerNeverUsed(
          passkeyUsers, passkeyCredentials, jdbc, identity.id(), "Laptop");

      MvcResult result = browser.perform(get("/who/me/passkeys")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode first = body(result).get(0);
      assertThat(first.propertyNames())
          .containsExactlyInAnyOrder("credentialId", "label", "createdAt", "lastUsedAt");
      assertThat(first.get("lastUsedAt").isNull()).isTrue();
      assertThat(first.get("createdAt").isString()).isTrue();
    }
  }

  @Nested
  class LinkedAccounts {

    @Test
    void the_list_keeps_its_shape() throws Exception {
      federatedCredentials.link(identity, PROVIDER.issuer(), "jackson");

      MvcResult result = browser.perform(get("/who/me/accounts")).andReturn();

      assertThat(result.getResponse().getStatus())
          .as(result.getResponse().getContentAsString())
          .isEqualTo(200);
      JsonNode first = body(result).get(0);
      assertThat(first.propertyNames()).containsExactlyInAnyOrder("id", "provider", "linkedAt");
      assertThat(first.get("provider").asString()).isEqualTo("stub");
      assertThat(first.get("linkedAt").isString()).isTrue();
    }
  }

  @Nested
  class ApiKeys {

    private MvcResult create() throws Exception {
      TestApiKeys.grant(rbac, identity);
      return browser
          .perform(
              post("/who/me/api-keys")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"ci\",\"lifetime\":\"P1D\",\"scopes\":[\""
                          + TestApiKeys.PERMISSION
                          + "\"]}"))
          .andReturn();
    }

    @Test
    void a_created_key_keeps_its_shape() throws Exception {
      MvcResult result = create();

      assertThat(result.getResponse().getStatus())
          .as(result.getResponse().getContentAsString())
          .isEqualTo(201);
      assertThat(body(result).propertyNames())
          .containsExactlyInAnyOrder("id", "name", "scopes", "expiresAt", "key");
      assertThat(body(result).get("expiresAt").isString()).isTrue();
    }

    @Test
    void the_list_keeps_a_null_last_used_at() throws Exception {
      assertThat(create().getResponse().getStatus()).isEqualTo(201);

      MvcResult result = browser.perform(get("/who/me/api-keys")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode first = body(result).get(0);
      assertThat(first.propertyNames())
          .containsExactlyInAnyOrder(
              "id", "name", "scopes", "createdAt", "expiresAt", "lastUsedAt");
      assertThat(first.get("lastUsedAt").isNull()).isTrue();
    }
  }

  @Nested
  class Admin {

    @BeforeEach
    void anAdmin() {
      Role role = rbac.createRole("jackson-admin-" + identity.id());
      rbac.addPermissionToRole(role, WhoAdminPermissions.IDENTITIES_READ);
      rbac.addPermissionToRole(role, WhoAdminPermissions.IDENTITIES_MANAGE);
      rbac.assignRoleToIdentity(identity, role);
    }

    @Test
    void a_page_keeps_its_shape() throws Exception {
      MvcResult result = browser.perform(get("/who/identities?size=1")).andReturn();

      assertThat(result.getResponse().getStatus())
          .as(result.getResponse().getContentAsString())
          .isEqualTo(200);
      assertThat(body(result).propertyNames())
          .containsExactlyInAnyOrder("items", "page", "size", "total");
      JsonNode first = body(result).get("items").get(0);
      assertThat(first.propertyNames())
          .containsExactlyInAnyOrder(
              "id", "status", "createdAt", "roles", "emailAddresses", "credentials");
      assertThat(first.get("createdAt").isString()).isTrue();
      assertThat(first.get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void an_invitation_keeps_its_shape() throws Exception {
      MvcResult result =
          browser
              .perform(
                  post("/who/invitations")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"email\":\"invitee-" + UUID.randomUUID() + "@example.com\"}"))
              .andReturn();

      assertThat(result.getResponse().getStatus())
          .as(result.getResponse().getContentAsString())
          .isEqualTo(201);
      assertThat(body(result).propertyNames()).containsExactlyInAnyOrder("identityId", "linkSent");
    }
  }

  @Nested
  class Errors {

    @Test
    void under_whos_base_path_the_answer_is_whos_problem_whatever_the_application_advises()
        throws Exception {
      MvcResult result =
          browser.perform(delete("/who/me/sessions/" + UUID.randomUUID())).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(404);
      assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
      assertThat(body(result).propertyNames())
          .containsExactlyInAnyOrder("type", "title", "status", "detail", "code");
      assertThat(body(result).get("code").asString()).isEqualTo("session-not-found");
    }

    @Test
    void a_who_exception_on_the_applications_path_is_the_applications_with_whos_header()
        throws Exception {
      MvcResult result = browser.perform(get("/app/orders/1")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(418);
      assertThat(body(result).get("app").asString()).isEqualTo("SessionNotFoundException");
      assertThat(result.getResponse().getHeader("Who-Error")).isEqualTo("session-not-found");
    }
  }
}
