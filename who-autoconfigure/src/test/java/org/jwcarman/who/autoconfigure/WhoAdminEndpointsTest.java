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
import static org.springframework.security.config.Customizer.withDefaults;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.admin.WhoAdminPermissions;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.repository.IdentityRepository;
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
 * who's admin endpoints, at {@code /who/identities} and {@code /who/roles}, on a browser chain and
 * on a stateless chain of the application's own, against PostgreSQL.
 */
@SpringBootTest(
    classes = WhoAdminEndpointsTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoAdminEndpointsTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String ALICE_TOKEN = "alice-token";
  static final String BOB_TOKEN = "bob-token";
  static final String ADDRESS = "alice@example.com";
  static final String ADMIN_ROLE = "who-admin-test-admin";
  static final String EDITOR_ROLE = "who-admin-test-editor";
  static final JsonMapper JSON = new JsonMapper();
  static final List<EmailLinkMessage> SENT = new CopyOnWriteArrayList<>();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
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
        String subject =
            switch (token) {
              case ALICE_TOKEN -> "alice";
              case BOB_TOKEN -> "bob";
              default -> throw new BadJwtException("not a good token");
            };
        Instant now = Instant.now();
        return new Jwt(
            token,
            now,
            now.plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("iss", ISSUER, "sub", subject));
      };
    }

    /** A request that carries its own credential goes to the stateless chain. */
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
  @Autowired private IdentityRepository identityRepository;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private RbacService rbacService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private JdbcClient jdbcClient;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
  private Role adminRole;
  private Role editorRole;
  private Identity alice;
  private Identity bob;

  private Role role(String name, String... permissions) {
    Role role = rbacService.findRole(name).orElseGet(() -> rbacService.createRole(name));
    for (String permission : permissions) {
      rbacService.addPermissionToRole(role, permission);
    }
    return role;
  }

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    adminRole =
        role(
            ADMIN_ROLE, WhoAdminPermissions.IDENTITIES_READ, WhoAdminPermissions.IDENTITIES_MANAGE);
    editorRole = role(EDITOR_ROLE, "task.write");
    alice = whoService.createIdentity();
    rbacService.assignRoleToIdentity(alice, adminRole);
    federatedCredentialService.link(alice, ISSUER, "alice");
    emailCredentialService.register(alice, ADDRESS);
    bob = whoService.createIdentity();
    federatedCredentialService.link(bob, ISSUER, "bob");
    emailCredentialService.register(bob, "bob@example.com");
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

  /** A request from the browser: its cookies, and a CSRF token unless it is a GET. */
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

  /** A request carrying a credential header and no cookies, as an API client sends. */
  private HttpResponse<String> carrying(
      String header, String value, String method, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(path))
            .header(header, value)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> bearer(String token, String method, String path, String body)
      throws Exception {
    return carrying("Authorization", "Bearer " + token, method, path, body);
  }

  private HttpResponse<String> asAlice(String method, String path, String body) throws Exception {
    return bearer(ALICE_TOKEN, method, path, body);
  }

  private void signIn() throws Exception {
    assertThat(browser("POST", "/who/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(browser("POST", "/who/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
        .isEqualTo(200);
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  private static List<String> ids(HttpResponse<String> response) {
    return json(response)
        .get("items")
        .valueStream()
        .map(item -> item.get("id").asString())
        .toList();
  }

  private static String status(String status) {
    return "{\"status\":\"" + status + "\"}";
  }

  @Nested
  class Listing {

    @Test
    void lists_identities_paged_in_creation_order() throws Exception {
      Identity carol = whoService.createIdentity();

      HttpResponse<String> first = asAlice("GET", "/who/identities?size=2", null);
      HttpResponse<String> second = asAlice("GET", "/who/identities?size=2&page=1", null);

      assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
      assertThat(ids(first)).containsExactly(alice.id().toString(), bob.id().toString());
      assertThat(json(first).get("total").asLong()).isEqualTo(3);
      assertThat(ids(second)).containsExactly(carol.id().toString());
      JsonNode view = json(first).get("items").get(0);
      assertThat(view.get("roles").valueStream().map(JsonNode::asString))
          .containsExactly(ADMIN_ROLE);
      assertThat(view.get("emailAddresses").get(0).asString()).isEqualTo(ADDRESS);
      assertThat(view.get("credentials").get("email").asLong()).isEqualTo(1);
      // alice's issuer and subject are a JWT's, which no provider registration signs in with
      assertThat(view.get("credentials").path("account").asLong()).isZero();
      assertThat(Instant.parse(view.get("createdAt").asString()).truncatedTo(ChronoUnit.MILLIS))
          .isEqualTo(
              identityRepository
                  .findById(alice.id())
                  .orElseThrow()
                  .createdAt()
                  .truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void filters_by_status_and_role() throws Exception {
      Identity carol = whoService.createIdentity();
      rbacService.assignRoleToIdentity(bob, editorRole);
      rbacService.assignRoleToIdentity(carol, editorRole);
      whoService.deactivate(carol.id());

      assertThat(ids(asAlice("GET", "/who/identities?role=" + EDITOR_ROLE, null)))
          .containsExactly(bob.id().toString(), carol.id().toString());
      assertThat(
              ids(asAlice("GET", "/who/identities?role=" + EDITOR_ROLE + "&status=DISABLED", null)))
          .containsExactly(carol.id().toString());
      assertThat(ids(asAlice("GET", "/who/identities?status=ACTIVE", null)))
          .containsExactly(alice.id().toString(), bob.id().toString());
    }

    @Test
    void finds_by_email_address() throws Exception {
      HttpResponse<String> found = asAlice("GET", "/who/identities?email=BOB@example.com", null);
      HttpResponse<String> missed = asAlice("GET", "/who/identities?email=x@example.com", null);

      assertThat(ids(found)).containsExactly(bob.id().toString());
      assertThat(missed.statusCode()).isEqualTo(200);
      assertThat(ids(missed)).isEmpty();
      assertThat(json(missed).get("total").asLong()).isZero();
    }

    @Test
    void size_above_100_is_bad_request() throws Exception {
      HttpResponse<String> response = asAlice("GET", "/who/identities?size=101", null);

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Who-Error")).contains("invalid-request");
    }

    @Test
    void reads_one_identity_and_the_roles() throws Exception {
      HttpResponse<String> one = asAlice("GET", "/who/identities/" + bob.id(), null);
      HttpResponse<String> roles = asAlice("GET", "/who/roles", null);

      assertThat(one.statusCode()).isEqualTo(200);
      assertThat(json(one).get("id").asString()).isEqualTo(bob.id().toString());
      assertThat(json(one).get("status").asString()).isEqualTo("ACTIVE");
      assertThat(roles.statusCode()).isEqualTo(200);
      JsonNode admin =
          json(roles)
              .valueStream()
              .filter(r -> r.get("name").asString().equals(ADMIN_ROLE))
              .findFirst()
              .orElseThrow();
      assertThat(admin.get("permissions").valueStream().map(JsonNode::asString))
          .containsExactly(
              WhoAdminPermissions.IDENTITIES_MANAGE, WhoAdminPermissions.IDENTITIES_READ);
    }

    @Test
    void an_unknown_role_filters_to_an_empty_page() throws Exception {
      HttpResponse<String> response = asAlice("GET", "/who/identities?role=no-such-role", null);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(ids(response)).isEmpty();
      assertThat(json(response).get("total").asLong()).isZero();
    }

    @Test
    void an_unknown_identity_is_not_found() throws Exception {
      HttpResponse<String> response =
          asAlice("GET", "/who/identities/00000000-0000-0000-0000-000000000000", null);

      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.headers().firstValue("Who-Error")).contains("identity-not-found");
    }
  }

  @Nested
  class Authorization {

    @Test
    void signed_out_is_unauthorized() throws Exception {
      HttpResponse<String> response = browser("GET", "/who/identities", null);

      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(response.headers().firstValue("Who-Error")).contains("not-signed-in");
    }

    @Test
    void without_read_permission_is_forbidden() throws Exception {
      HttpResponse<String> response = bearer(BOB_TOKEN, "GET", "/who/identities", null);

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue("Who-Error")).contains("permission-required");
      assertThat(bearer(BOB_TOKEN, "GET", "/who/roles", null).statusCode()).isEqualTo(403);
    }

    @Test
    void scoped_api_key_may_administer() throws Exception {
      String key =
          apiKeyService
              .create(
                  alice,
                  "admin",
                  Duration.ofDays(1),
                  Set.of(
                      WhoAdminPermissions.IDENTITIES_READ, WhoAdminPermissions.IDENTITIES_MANAGE))
              .key();

      HttpResponse<String> listed = carrying("X-API-Key", key, "GET", "/who/identities", null);
      HttpResponse<String> deactivated =
          carrying("X-API-Key", key, "PATCH", "/who/identities/" + bob.id(), status("DISABLED"));

      assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
      assertThat(deactivated.statusCode()).as(deactivated.body()).isEqualTo(200);
      assertThat(identityRepository.findById(bob.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.DISABLED);
    }

    @Test
    void an_api_key_not_scoped_to_it_is_forbidden() throws Exception {
      String key =
          apiKeyService
              .create(
                  alice, "reader", Duration.ofDays(1), Set.of(WhoAdminPermissions.IDENTITIES_READ))
              .key();

      HttpResponse<String> response =
          carrying("X-API-Key", key, "PATCH", "/who/identities/" + bob.id(), status("DISABLED"));

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(identityRepository.findById(bob.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }
  }

  @Nested
  class Managing {

    @Test
    void deactivates_and_reactivates() throws Exception {
      signIn();

      HttpResponse<String> off =
          browser("PATCH", "/who/identities/" + bob.id(), status("DISABLED"));
      assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
      assertThat(json(off).get("status").asString()).isEqualTo("DISABLED");
      // a deactivated identity's credentials no longer sign it in
      assertThat(bearer(BOB_TOKEN, "GET", "/who/me", null).statusCode()).isEqualTo(401);

      HttpResponse<String> on = browser("PATCH", "/who/identities/" + bob.id(), status("ACTIVE"));
      assertThat(on.statusCode()).isEqualTo(200);
      assertThat(json(on).get("status").asString()).isEqualTo("ACTIVE");
      assertThat(bearer(BOB_TOKEN, "GET", "/who/me", null).statusCode()).isEqualTo(200);
    }

    @Test
    void cannot_deactivate_oneself() throws Exception {
      signIn();

      HttpResponse<String> response =
          browser("PATCH", "/who/identities/" + alice.id(), status("DISABLED"));

      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.headers().firstValue("Who-Error")).contains("self-deactivation");
      assertThat(identityRepository.findById(alice.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void assigns_and_removes_roles_idempotently() throws Exception {
      // alice may grant the editor role only once she holds its permission herself
      rbacService.assignRoleToIdentity(alice, role("who-admin-test-writer", "task.write"));
      String path = "/who/identities/" + bob.id() + "/roles/" + EDITOR_ROLE;

      assertThat(asAlice("PUT", path, null).statusCode()).isEqualTo(204);
      assertThat(asAlice("PUT", path, null).statusCode()).isEqualTo(204);
      assertThat(rbacService.findRolesForIdentity(bob.id())).containsExactly(editorRole);

      assertThat(asAlice("DELETE", path, null).statusCode()).isEqualTo(204);
      assertThat(asAlice("DELETE", path, null).statusCode()).isEqualTo(204);
      assertThat(rbacService.findRolesForIdentity(bob.id())).isEmpty();
    }

    @Test
    void a_role_beyond_the_callers_permissions_is_forbidden() throws Exception {
      HttpResponse<String> response =
          asAlice("PUT", "/who/identities/" + bob.id() + "/roles/" + EDITOR_ROLE, null);

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue("Who-Error")).contains("role-grant-forbidden");
      assertThat(rbacService.findRolesForIdentity(bob.id())).isEmpty();
    }

    @Test
    void an_api_key_grants_only_within_its_scopes() throws Exception {
      // alice holds the editor role's permission; her key is scoped to manage alone
      rbacService.assignRoleToIdentity(alice, editorRole);
      String key =
          apiKeyService
              .create(
                  alice,
                  "manager",
                  Duration.ofDays(1),
                  Set.of(WhoAdminPermissions.IDENTITIES_MANAGE))
              .key();

      HttpResponse<String> response =
          carrying(
              "X-API-Key",
              key,
              "PUT",
              "/who/identities/" + bob.id() + "/roles/" + EDITOR_ROLE,
              null);

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(response.headers().firstValue("Who-Error")).contains("role-grant-forbidden");
      assertThat(rbacService.findRolesForIdentity(bob.id())).isEmpty();
    }

    @Test
    void unknown_role_is_not_found() throws Exception {
      String path = "/who/identities/" + bob.id() + "/roles/no-such-role";

      for (String method : List.of("PUT", "DELETE")) {
        HttpResponse<String> response = asAlice(method, path, null);
        assertThat(response.statusCode()).as(method).isEqualTo(404);
        assertThat(response.headers().firstValue("Who-Error")).contains("unknown-role");
      }
    }

    @Test
    void a_browser_without_csrf_cannot_change_anything() throws Exception {
      signIn();

      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(uri("/who/identities/" + bob.id()))
                  .header("Content-Type", "application/json")
                  .method("PATCH", HttpRequest.BodyPublishers.ofString(status("DISABLED"))));

      assertThat(response.statusCode()).isEqualTo(403);
      assertThat(identityRepository.findById(bob.id()).orElseThrow().status())
          .isEqualTo(IdentityStatus.ACTIVE);
    }
  }

  @Nested
  class Last_admin {

    private String adminRole(Identity identity) {
      return "/who/identities/" + identity.id() + "/roles/" + ADMIN_ROLE;
    }

    @Test
    void the_last_admin_cannot_remove_their_own_admin_role() throws Exception {
      HttpResponse<String> response = asAlice("DELETE", adminRole(alice), null);

      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(response.headers().firstValue("Who-Error")).contains("last-admin");
      assertThat(rbacService.findRolesForIdentity(alice.id())).contains(adminRole);
    }

    @Test
    void with_another_admin_one_may_step_down_or_be_deactivated() throws Exception {
      rbacService.assignRoleToIdentity(bob, adminRole);

      assertThat(asAlice("PATCH", "/who/identities/" + bob.id(), status("DISABLED")).statusCode())
          .isEqualTo(200);
      // bob no longer counts: alice is the last again
      assertThat(asAlice("DELETE", adminRole(alice), null).statusCode()).isEqualTo(409);

      assertThat(asAlice("PATCH", "/who/identities/" + bob.id(), status("ACTIVE")).statusCode())
          .isEqualTo(200);
      assertThat(asAlice("DELETE", adminRole(alice), null).statusCode()).isEqualTo(204);
      assertThat(rbacService.findRolesForIdentity(alice.id())).doesNotContain(adminRole);
    }
  }
}
