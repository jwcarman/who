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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link WhoSecurity#all()} mounts every sign-in method on the classpath into one chain. Browser
 * requests keep CSRF protection; a request that carries an API key or a bearer token is signed in
 * by it, or refused, and so needs no CSRF token and never gets a session.
 */
@SpringBootTest(
    classes = WhoSecurityAllTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "who.security.public-paths=/open/**",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSecurityAllTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String GOOD_TOKEN = "good-token";
  static final String SUBJECT = "alice";
  static final String ADDRESS = "alice@example.com";
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
    EmailLinkSender emailLinkSender() {
      return SENT::add;
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    @Bean
    JwtDecoder jwtDecoder() {
      return WhoSecurityAllTest::decode;
    }

    @Bean
    PingController pingController() {
      return new PingController();
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http, WhoPublicPaths publicPaths) {
      http.with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(
              a ->
                  a.requestMatchers(publicPaths.patterns())
                      .permitAll()
                      .anyRequest()
                      .authenticated());
      return http.build();
    }
  }

  static Jwt decode(String token) {
    if (!GOOD_TOKEN.equals(token)) {
      throw new BadJwtException("not a good token");
    }
    Instant now = Instant.now();
    return new Jwt(
        token,
        now,
        now.plusSeconds(60),
        Map.of("alg", "none"),
        Map.of("iss", ISSUER, "sub", SUBJECT));
  }

  @RestController
  static class PingController {

    @GetMapping({"/open/ping", "/secure/ping"})
    String get(@AuthenticationPrincipal Object principal) {
      return String.valueOf(principal);
    }

    @PostMapping("/secure/ping")
    String post(@AuthenticationPrincipal Object principal) {
      return String.valueOf(principal);
    }
  }

  @LocalServerPort private int port;
  @Autowired private WhoService whoService;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private EmailCredentialService emailCredentialService;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
  private String apiKey;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    Identity identity = whoService.createIdentity();
    apiKey = apiKeyService.create(identity, "test");
    federatedCredentialService.link(identity, ISSUER, SUBJECT);
    emailCredentialService.register(identity, ADDRESS);
    SENT.clear();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder post(String path) {
    return HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody());
  }

  /** Loads a public page so the browser has an {@code XSRF-TOKEN} cookie, and returns it. */
  private String csrfToken() throws Exception {
    send(HttpRequest.newBuilder(uri("/open/ping")).GET());
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  @Nested
  class Csrf {

    @Test
    void a_browser_post_without_a_csrf_token_is_forbidden() throws Exception {
      assertThat(send(post("/secure/ping")).statusCode()).isEqualTo(403);
    }

    @Test
    void a_post_with_a_valid_api_key_needs_no_csrf_token() throws Exception {
      assertThat(send(post("/secure/ping").header("X-API-Key", apiKey)).statusCode())
          .isEqualTo(200);
    }

    @Test
    void a_post_with_a_valid_bearer_token_needs_no_csrf_token() throws Exception {
      assertThat(
              send(post("/secure/ping").header("Authorization", "Bearer " + GOOD_TOKEN))
                  .statusCode())
          .isEqualTo(200);
    }

    @Test
    void a_blank_api_key_header_does_not_skip_csrf() throws Exception {
      assertThat(send(post("/secure/ping").header("X-API-Key", " ")).statusCode()).isEqualTo(403);
    }

    @Test
    void a_bogus_bearer_token_is_unauthorized_not_let_through() throws Exception {
      assertThat(send(post("/secure/ping").header("Authorization", "Bearer bogus")).statusCode())
          .isEqualTo(401);
    }

    @Test
    void basic_credentials_do_not_skip_csrf() throws Exception {
      assertThat(
              send(post("/secure/ping").header("Authorization", "Basic YWxpY2U6c2VjcmV0"))
                  .statusCode())
          .isEqualTo(403);
    }
  }

  private HttpRequest.Builder json(String path, String body) throws Exception {
    return HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/json")
        .header("X-XSRF-TOKEN", csrfToken())
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  /** Signs the browser in with an emailed link, so later requests carry a session cookie. */
  private void signIn() throws Exception {
    assertThat(send(json("/ott/generate", "{\"email\":\"" + ADDRESS + "\"}")).statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(send(json("/login/ott", "{\"token\":\"" + token + "\"}")).statusCode())
        .isEqualTo(200);
  }

  /**
   * A request with a session is signed in by its cookie, which a forged request carries too. A
   * header that does not sign the request in itself must not exempt it from CSRF.
   */
  @Nested
  class Csrf_with_a_session {

    @BeforeEach
    void signedIn() throws Exception {
      signIn();
    }

    @Test
    void a_post_without_a_csrf_token_is_forbidden() throws Exception {
      assertThat(send(post("/secure/ping")).statusCode()).isEqualTo(403);
    }

    @Test
    void a_post_with_a_csrf_token_is_signed_in_by_the_session() throws Exception {
      assertThat(send(post("/secure/ping").header("X-XSRF-TOKEN", csrfToken())).statusCode())
          .isEqualTo(200);
    }

    @Test
    void a_junk_api_key_does_not_skip_csrf() throws Exception {
      assertThat(send(post("/secure/ping").header("X-API-Key", "junk")).statusCode())
          .isEqualTo(403);
    }

    @Test
    void a_blank_api_key_does_not_skip_csrf() throws Exception {
      assertThat(send(post("/secure/ping").header("X-API-Key", " ")).statusCode()).isEqualTo(403);
    }

    @Test
    void a_bogus_bearer_token_is_unauthorized() throws Exception {
      assertThat(send(post("/secure/ping").header("Authorization", "Bearer bogus")).statusCode())
          .isEqualTo(401);
    }
  }

  @Nested
  class Sessions {

    @Test
    void an_api_key_request_gets_no_session_cookie() throws Exception {
      HttpResponse<String> response =
          send(HttpRequest.newBuilder(uri("/secure/ping")).GET().header("X-API-Key", apiKey));

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().allValues("Set-Cookie"))
          .noneMatch(c -> c.startsWith("JSESSIONID") || c.startsWith("SESSION"));
    }

    @Test
    void a_bearer_request_gets_no_session_cookie() throws Exception {
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(uri("/secure/ping"))
                  .GET()
                  .header("Authorization", "Bearer " + GOOD_TOKEN));

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().allValues("Set-Cookie"))
          .noneMatch(c -> c.startsWith("JSESSIONID") || c.startsWith("SESSION"));
    }

    @Test
    void a_signed_out_request_is_unauthorized() throws Exception {
      assertThat(send(HttpRequest.newBuilder(uri("/secure/ping")).GET()).statusCode())
          .isEqualTo(401);
    }
  }

  @Nested
  class Public_paths {

    @Test
    void email_link_endpoints_are_public() throws Exception {
      String token = csrfToken();
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(uri("/ott/generate"))
                  .header("Content-Type", "application/json")
                  .header("X-XSRF-TOKEN", token)
                  .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@example.com\"}")));

      assertThat(response.statusCode()).isNotIn(401, 403);
    }

    @Test
    void passkey_sign_in_options_are_public() throws Exception {
      String token = csrfToken();
      HttpResponse<String> response =
          send(
              HttpRequest.newBuilder(uri("/webauthn/authenticate/options"))
                  .header("X-XSRF-TOKEN", token)
                  .POST(HttpRequest.BodyPublishers.noBody()));

      assertThat(response.statusCode()).isNotIn(401, 403);
    }
  }

  /** A JWT module mounted without a way to verify tokens. */
  @Nested
  class Without_a_jwt_decoder {

    @Test
    void mounting_jwt_fails_startup_naming_the_issuer_property() {
      new WebApplicationContextRunner()
          .withConfiguration(
              WhoTestAutoConfigurations.who(
                  SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
          .withUserConfiguration(Database.class, OwnChain.class)
          .withPropertyValues("who.email-link.enabled=false", "who.passkey.enabled=false")
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .isNotNull()
                      .rootCause()
                      .isInstanceOfSatisfying(
                          WhoConfigurationException.class,
                          e -> {
                            assertThat(e.property())
                                .isEqualTo("spring.security.oauth2.resourceserver.jwt.issuer-uri");
                            assertThat(e.action()).contains("who.jwt.enabled=false");
                          }));
    }

    @Test
    void with_jwt_turned_off_the_chain_starts() {
      new WebApplicationContextRunner()
          .withConfiguration(
              WhoTestAutoConfigurations.who(
                  SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
          .withUserConfiguration(Database.class, OwnChain.class)
          .withPropertyValues(
              "who.email-link.enabled=false", "who.passkey.enabled=false", "who.jwt.enabled=false")
          .run(context -> assertThat(context).hasNotFailed());
    }
  }

  /** A JWT module whose tokens would be accepted whatever audience they were issued to. */
  @Nested
  class Without_audience_validation {

    private final WebApplicationContextRunner runner =
        new WebApplicationContextRunner()
            .withConfiguration(
                WhoTestAutoConfigurations.who(
                    SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(Database.class, OwnChain.class)
            .withPropertyValues("who.email-link.enabled=false", "who.passkey.enabled=false");

    @Test
    void issuer_uri_without_audiences_fails_startup() {
      runner
          .withPropertyValues(
              "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com")
          .run(context -> assertAudienceFailure(context.getStartupFailure()));
    }

    @Test
    void jwk_set_uri_without_audiences_fails_startup() {
      runner
          .withPropertyValues(
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.com/jwks")
          .run(context -> assertAudienceFailure(context.getStartupFailure()));
    }

    @Test
    void public_key_location_without_audiences_fails_startup() {
      runner
          .withPropertyValues(
              "spring.security.oauth2.resourceserver.jwt.public-key-location=classpath:key.pem")
          .run(context -> assertAudienceFailure(context.getStartupFailure()));
    }

    @Test
    void audiences_set_starts() {
      runner
          .withUserConfiguration(Decoder.class)
          .withPropertyValues(
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.com/jwks",
              "spring.security.oauth2.resourceserver.jwt.audiences=api://who")
          .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void audiences_as_a_list_starts() {
      runner
          .withUserConfiguration(Decoder.class)
          .withPropertyValues(
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.com/jwks",
              "spring.security.oauth2.resourceserver.jwt.audiences[0]=api://who",
              "spring.security.oauth2.resourceserver.jwt.audiences[1]=api://other")
          .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void an_application_decoder_without_the_properties_starts() {
      runner
          .withUserConfiguration(Decoder.class)
          .run(context -> assertThat(context).hasNotFailed());
    }

    private static void assertAudienceFailure(Throwable failure) {
      assertThat(failure)
          .isNotNull()
          .rootCause()
          .isInstanceOfSatisfying(
              WhoConfigurationException.class,
              e -> {
                assertThat(e.property())
                    .isEqualTo("spring.security.oauth2.resourceserver.jwt.audiences");
                assertThat(e.action()).contains("who.jwt.enabled=false");
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Decoder {
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        throw new BadJwtException("not used");
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Database {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnChain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoSecurity.all(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }
}
