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
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.NewApiKey;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
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
 * The caller's API keys at {@code /who/me/api-keys}, on a browser chain and on a stateless chain of
 * the application's own: a browser session and a bearer token manage keys, a key reads only itself.
 */
@SpringBootTest(
    classes = ApiKeyEndpointsTest.App.class,
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
class ApiKeyEndpointsTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String ALICE_TOKEN = "alice-token";
  static final String ADDRESS = "alice@example.com";
  static final String KEYS = "/who/me/api-keys";
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
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private RbacService rbacService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private JdbcClient jdbcClient;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
  private Identity alice;
  private Identity bob;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    alice = whoService.createIdentity();
    TestApiKeys.grant(rbacService, alice);
    federatedCredentialService.link(alice, ISSUER, "alice");
    emailCredentialService.register(alice, ADDRESS);
    bob = whoService.createIdentity();
    TestApiKeys.grant(rbacService, bob);
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

  private HttpResponse<String> bearer(String method, String path, String body) throws Exception {
    return carrying("Authorization", "Bearer " + ALICE_TOKEN, method, path, body);
  }

  private HttpResponse<String> withKey(String key, String method, String path) throws Exception {
    return carrying("X-API-Key", key, method, path, null);
  }

  private void signIn() throws Exception {
    assertThat(browser("POST", "/who/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(browser("POST", "/who/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
        .isEqualTo(200);
  }

  private static String createBody(String name) {
    return "{\"name\":\""
        + name
        + "\",\"lifetime\":\"P30D\",\"scopes\":[\""
        + TestApiKeys.PERMISSION
        + "\"]}";
  }

  private static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  @Nested
  class From_a_browser {

    @Test
    void creates_lists_reads_with_the_key_and_revokes() throws Exception {
      signIn();

      HttpResponse<String> created = browser("POST", KEYS, createBody("CI"));
      assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
      String id = json(created).get("id").asString();
      String key = json(created).get("key").asString();

      HttpResponse<String> listed = browser("GET", KEYS, null);
      assertThat(listed.statusCode()).isEqualTo(200);
      assertThat(listed.body()).contains(id).doesNotContain(key);

      HttpResponse<String> current = withKey(key, "GET", KEYS + "/current");
      assertThat(current.statusCode()).isEqualTo(200);
      assertThat(json(current).get("id").asString()).isEqualTo(id);
      assertThat(current.body()).doesNotContain(key);

      assertThat(browser("DELETE", KEYS + "/" + id, null).statusCode()).isEqualTo(204);
      assertThat(withKey(key, "GET", KEYS + "/current").statusCode()).isEqualTo(401);
    }

    @Test
    void current_without_a_key_is_not_found() throws Exception {
      signIn();

      assertThat(browser("GET", KEYS + "/current", null).statusCode()).isEqualTo(404);
    }

    @Test
    void scope_the_owner_lacks_is_bad_request() throws Exception {
      signIn();

      HttpResponse<String> response =
          browser("POST", KEYS, "{\"name\":\"CI\",\"lifetime\":\"P1D\",\"scopes\":[\"not.held\"]}");

      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.headers().firstValue("Who-Error")).contains("invalid-api-key-request");
    }

    @Test
    void signed_out_is_unauthorized() throws Exception {
      assertThat(browser("GET", KEYS, null).statusCode()).isEqualTo(401);
    }
  }

  @Nested
  class With_a_bearer_token_on_the_stateless_chain {

    @Test
    void creates_lists_rotates_and_revokes() throws Exception {
      HttpResponse<String> created = bearer("POST", KEYS, createBody("CI"));
      assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
      String id = json(created).get("id").asString();
      String key = json(created).get("key").asString();

      assertThat(bearer("GET", KEYS, null).body()).contains(id).doesNotContain(key);

      HttpResponse<String> rotated =
          bearer("POST", KEYS + "/" + id + "/rotations", "{\"overlap\":\"PT0S\"}");
      assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(201);
      String newId = json(rotated).get("id").asString();
      String newKey = json(rotated).get("key").asString();
      assertThat(withKey(key, "GET", KEYS + "/current").statusCode()).isEqualTo(401);
      assertThat(withKey(newKey, "GET", KEYS + "/current").statusCode()).isEqualTo(200);

      assertThat(bearer("DELETE", KEYS + "/" + newId, null).statusCode()).isEqualTo(204);
      assertThat(withKey(newKey, "GET", KEYS + "/current").statusCode()).isEqualTo(401);
    }

    @Test
    void another_identitys_id_is_not_found() throws Exception {
      NewApiKey bobs =
          apiKeyService.create(bob, "Bob's", Duration.ofDays(1), Set.of(TestApiKeys.PERMISSION));

      assertThat(bearer("DELETE", KEYS + "/" + bobs.id(), null).statusCode()).isEqualTo(404);
      assertThat(
              bearer("POST", KEYS + "/" + bobs.id() + "/rotations", "{\"overlap\":\"PT0S\"}")
                  .statusCode())
          .isEqualTo(404);
      assertThat(withKey(bobs.key(), "GET", KEYS + "/current").statusCode()).isEqualTo(200);
    }

    @Test
    void current_without_a_key_is_not_found() throws Exception {
      assertThat(bearer("GET", KEYS + "/current", null).statusCode()).isEqualTo(404);
    }
  }

  @Nested
  class With_an_api_key {

    @Test
    void an_api_key_cannot_manage_keys() throws Exception {
      String key = TestApiKeys.create(apiKeyService, alice, "Own");

      assertThat(withKey(key, "GET", KEYS).statusCode()).isEqualTo(403);
      assertThat(carrying("X-API-Key", key, "POST", KEYS, createBody("Minted")).statusCode())
          .isEqualTo(403);
      assertThat(apiKeyService.findByIdentity(alice.id())).hasSize(1);
    }
  }
}
