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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.config.Customizer.withDefaults;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.admin.WhoAdminPermissions;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * who's JSON answers keep their shape whatever the application does to its own Jackson: here the
 * application names properties in snake case, writes dates as numbers and leaves nulls out, and
 * every answer still has who's camel-case names, ISO-8601 instants and its nulls.
 */
@SpringBootTest(
    classes = WhoJsonShapeTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "spring.session.jdbc.initialize-schema=always",
      "spring.jackson.property-naming-strategy=SNAKE_CASE",
      "spring.jackson.datatype.datetime.write-dates-as-timestamps=true",
      "spring.jackson.default-property-inclusion=non_null"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoJsonShapeTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String ALICE_TOKEN = "alice-token";
  static final String ADDRESS = "alice@example.com";
  static final String ADMIN_ROLE = "who-json-shape-admin";
  static final JsonMapper JSON = new JsonMapper();
  static final List<EmailLinkMessage> SENT = new CopyOnWriteArrayList<>();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    WhoTestPostgres.registerFreshDatabase(registry);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    WhoEmailSender whoEmailSender() {
      return email -> {
        if (email.source() instanceof EmailLinkMessage message) {
          SENT.add(message);
        }
      };
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        if (!ALICE_TOKEN.equals(token)) {
          throw new BadJwtException("not a good token");
        }
        Instant now = Instant.now();
        return new Jwt(
            token,
            now,
            now.plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("iss", ISSUER, "sub", "alice"));
      };
    }

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http) {
      http.securityMatcher(
              request ->
                  request.getHeader("Authorization") != null
                      || request.getHeader("X-API-Key") != null)
          .with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
      http.with(WhoWebSecurity.passwordless(), withDefaults())
          .authorizeHttpRequests(
              a ->
                  a.requestMatchers(publicPaths.patterns())
                      .permitAll()
                      .anyRequest()
                      .authenticated());
      return http.build();
    }
  }

  @LocalServerPort private int port;
  @Autowired private WhoService whoService;
  @Autowired private RbacService rbacService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private JdbcClient jdbcClient;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
  private Identity alice;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    Role admin =
        rbacService.findRole(ADMIN_ROLE).orElseGet(() -> rbacService.createRole(ADMIN_ROLE));
    rbacService.addPermissionToRole(admin, WhoAdminPermissions.IDENTITIES_READ);
    rbacService.addPermissionToRole(admin, WhoAdminPermissions.IDENTITIES_MANAGE);
    alice = whoService.createIdentity();
    rbacService.assignRoleToIdentity(alice, admin);
    federatedCredentialService.link(alice, ISSUER, "alice");
    emailCredentialService.register(alice, ADDRESS);
    SENT.clear();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String csrfToken() throws Exception {
    send(HttpRequest.newBuilder(uri("/who/me")).GET());
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<String> browser(String method, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (!"GET".equals(method)) {
      request.header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken());
    }
    return send(request);
  }

  private HttpResponse<String> asAlice(String method, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer " + ALICE_TOKEN)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode json(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isLessThan(300);
    return JSON.readTree(response.body());
  }

  private static List<String> names(JsonNode node) {
    return node.propertyNames().stream().toList();
  }

  private static void assertInstant(JsonNode value) {
    assertThat(value.isString()).as("%s is a string", value).isTrue();
    assertThatCode(() -> Instant.parse(value.asString())).doesNotThrowAnyException();
  }

  @Nested
  class Api_keys {

    @Test
    void the_new_key_has_camel_case_names_and_an_iso_expiry() throws Exception {
      JsonNode created =
          json(
              asAlice(
                  "POST",
                  "/who/me/api-keys",
                  "{\"name\":\"ci\",\"lifetime\":\"P30D\",\"scopes\":[\""
                      + WhoAdminPermissions.IDENTITIES_READ
                      + "\"]}"));

      assertThat(names(created)).containsExactly("id", "name", "scopes", "expiresAt", "key");
      assertInstant(created.get("expiresAt"));
    }

    @Test
    void the_list_has_camel_case_names_iso_instants_and_its_nulls() throws Exception {
      asAlice(
          "POST",
          "/who/me/api-keys",
          "{\"name\":\"ci\",\"lifetime\":\"P30D\",\"scopes\":[\""
              + WhoAdminPermissions.IDENTITIES_READ
              + "\"]}");

      JsonNode listed = json(asAlice("GET", "/who/me/api-keys", null)).get(0);

      assertThat(names(listed))
          .containsExactly("id", "name", "scopes", "createdAt", "expiresAt", "lastUsedAt");
      assertInstant(listed.get("createdAt"));
      assertInstant(listed.get("expiresAt"));
      assertThat(listed.get("lastUsedAt").isNull()).isTrue();
    }
  }

  @Nested
  class Admin {

    @Test
    void a_page_of_identities_has_camel_case_names_and_iso_instants() throws Exception {
      JsonNode page = json(asAlice("GET", "/who/identities", null));

      assertThat(names(page)).containsExactly("items", "page", "size", "total");
      JsonNode view = page.get("items").get(0);
      assertThat(names(view))
          .containsExactly("id", "status", "createdAt", "roles", "emailAddresses", "credentials");
      assertInstant(view.get("createdAt"));
    }

    @Test
    void one_identity_has_camel_case_names_and_an_iso_instant() throws Exception {
      JsonNode view = json(asAlice("GET", "/who/identities/" + alice.id(), null));

      assertThat(names(view))
          .containsExactly("id", "status", "createdAt", "roles", "emailAddresses", "credentials");
      assertInstant(view.get("createdAt"));
    }

    @Test
    void the_roles_have_their_names() throws Exception {
      JsonNode roles = json(asAlice("GET", "/who/roles", null));

      assertThat(roles.isArray()).as(roles.toString()).isTrue();
      assertThat(roles.valueStream().map(WhoJsonShapeTest::names))
          .as(roles.toString())
          .isNotEmpty()
          .allSatisfy(role -> assertThat(role).containsExactly("name", "permissions"));
    }

    @Test
    void an_invitation_has_camel_case_names() throws Exception {
      HttpResponse<String> response =
          asAlice("POST", "/who/invitations", "{\"email\":\"carol@example.com\"}");

      assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
      assertThat(names(json(response))).containsExactly("identityId", "linkSent");
    }
  }

  @Nested
  class Me {

    @Test
    void a_browser_session_has_camel_case_names_and_an_iso_instant() throws Exception {
      assertThat(browser("POST", "/who/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
          .isLessThan(300);
      String link = SENT.getLast().link();
      String token = link.substring(link.indexOf("#token=") + "#token=".length());
      assertThat(
              browser("POST", "/who/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
          .isEqualTo(200);

      JsonNode me = json(browser("GET", "/who/me", null));

      assertThat(names(me))
          .containsExactly(
              "identityId", "permissions", "signInMethod", "recentSignInUntil", "credentials");
      assertInstant(me.get("recentSignInUntil"));
    }

    @Test
    void a_bearer_token_keeps_its_null_recent_sign_in() throws Exception {
      JsonNode me = json(asAlice("GET", "/who/me", null));

      assertThat(me.has("recentSignInUntil")).isTrue();
      assertThat(me.get("recentSignInUntil").isNull()).isTrue();
    }
  }
}
