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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.test.StubOidcProvider;
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

/**
 * Who answers {@code /who/me} the same way on each kind of chain:
 *
 * <ul>
 *   <li>on a {@link WhoSecurity#all()} chain, an API key alone reads what any signed-in caller may,
 *       and a session cookie with an API-key header beside it is not a browser, so it may not do
 *       what only a browser may; the header never spares the cookie a CSRF token;
 *   <li>on a {@link WhoSecurity#stateless()} chain, a bearer token or an API key reads what any
 *       signed-in caller may, and is refused what only a browser may.
 * </ul>
 *
 * <p>The application sends a request to its stateless chain when it carries {@value #API_CHAIN}.
 */
@SpringBootTest(
    classes = WhoMeOnEveryChainTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "who.oidc.enabled=true",
      "who.oidc.failure-url=/signin",
      "spring.security.oauth2.client.registration.stub.client-id=client",
      "spring.security.oauth2.client.registration.stub.client-secret=secret",
      "spring.security.oauth2.client.registration.stub.scope=openid,email",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoMeOnEveryChainTest {

  static final StubOidcProvider PROVIDER = StubOidcProvider.start();
  static final String API_CHAIN = "X-Api-Chain";
  static final String ISSUER = "https://issuer.example.com";
  static final String TOKEN = "good-token";
  static final String SUBJECT = "alice";
  static final String ADDRESS = "alice@example.com";
  static final List<EmailLinkMessage> SENT = new CopyOnWriteArrayList<>();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
    registry.add("spring.security.oauth2.client.provider.stub.issuer-uri", PROVIDER::issuer);
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
        if (!TOKEN.equals(token)) {
          throw new BadJwtException("not a good token");
        }
        Instant now = Instant.now();
        return new Jwt(
            token,
            now,
            now.plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("iss", ISSUER, "sub", SUBJECT));
      };
    }

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http) {
      http.securityMatcher(request -> request.getHeader(API_CHAIN) != null)
          .with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain all(HttpSecurity http, WhoPublicPaths publicPaths) {
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

  @LocalServerPort private int port;
  @Autowired private WhoService whoService;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private FederatedCredentialService federatedCredentialService;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private RbacService rbacService;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
  private Identity alice;
  private String apiKey;
  private EmailCredential emailCredential;
  private FederatedCredential account;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    alice = whoService.createIdentity();
    emailCredential = emailCredentialService.register(alice, ADDRESS);
    account = federatedCredentialService.link(alice, ISSUER, SUBJECT);
    TestApiKeys.grant(rbacService, alice);
    apiKey = TestApiKeys.create(apiKeyService, alice, "test");
    SENT.clear();
  }

  private String path(String template) {
    return template
        .replace("{email}", emailCredential.id().toString())
        .replace("{account}", account.id().toString());
  }

  /**
   * Sends a request with the browser's cookies, a CSRF token unless it is a GET or {@code csrf} is
   * false, and the given extra headers.
   */
  private HttpResponse<String> send(
      String method, String path, String body, boolean csrf, String... headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path(path)))
            .method(
                method,
                body == null || body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (!"GET".equals(method)) {
      request.header("Content-Type", "application/json");
      if (csrf) {
        request.header("X-XSRF-TOKEN", csrfToken());
      }
    }
    if (headers.length > 0) {
      request.headers(headers);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String csrfToken() throws Exception {
    client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/who/me")).GET().build(),
        HttpResponse.BodyHandlers.discarding());
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private void signIn() throws Exception {
    assertThat(
            send("POST", "/who/email/send", "{\"email\":\"" + ADDRESS + "\"}", true).statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(
            send("POST", "/who/email/sign-in", "{\"token\":\"" + token + "\"}", true).statusCode())
        .isEqualTo(200);
    SENT.clear();
  }

  @Nested
  class On_an_all_chain {

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/who/me/accounts", "/who/me/email-addresses"})
    void an_api_key_alone_reads_what_any_signed_in_caller_may(String path) throws Exception {
      HttpResponse<String> response = send("GET", path, null, false, "X-API-Key", apiKey);

      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            GET    | /who/me/sessions                  |
            GET    | /who/me/passkeys                  |
            POST   | /who/me/passkeys/options          |
            POST   | /who/me/email-addresses           | {"address":"new@example.com"}
            DELETE | /who/me/email-addresses/{email}   |
            POST   | /who/me/accounts/stub             |
            DELETE | /who/me/accounts/{account}        |
            POST   | /who/me/api-keys                  | {"name":"k","lifetime":"P1D","scopes":["test.api"]}
            """)
    void a_cookie_with_an_api_key_header_is_not_a_browser(String method, String path, String body)
        throws Exception {
      signIn();

      HttpResponse<String> response = send(method, path, body, true, "X-API-Key", apiKey);

      assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
      // nothing was done: the address and the account are still there
      assertThat(emailCredentialService.findAllFor(alice.id())).hasSize(1);
      assertThat(send("GET", "/who/me/accounts", null, false).body())
          .contains(account.id().toString());
    }

    @Test
    void a_bogus_api_key_header_does_not_spare_a_cookie_its_csrf_token() throws Exception {
      signIn();

      HttpResponse<String> response =
          send("POST", "/who/logout", null, false, "X-API-Key", "who_bogus");

      assertThat(response.statusCode()).isEqualTo(403);
      // still signed in: the forged sign-out did nothing
      assertThat(send("GET", "/who/me", null, false).statusCode()).isEqualTo(200);
    }

    @Test
    void a_bogus_bearer_header_does_not_spare_a_cookie_its_csrf_token() throws Exception {
      signIn();

      HttpResponse<String> response =
          send("POST", "/who/logout", null, false, "Authorization", "Bearer junk");

      assertThat(response.statusCode()).isEqualTo(403);
      // still signed in: the forged sign-out did nothing
      assertThat(send("GET", "/who/me", null, false).statusCode()).isEqualTo(200);
    }
  }

  @Nested
  class On_a_stateless_chain {

    @ParameterizedTest(name = "GET {0} with {1}")
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            /who/me/accounts        | Authorization
            /who/me/email-addresses | Authorization
            /who/me/accounts        | X-API-Key
            /who/me/email-addresses | X-API-Key
            """)
    void a_bearer_token_or_an_api_key_reads_what_any_signed_in_caller_may(
        String path, String header) throws Exception {
      String value = header.equals("Authorization") ? "Bearer " + TOKEN : apiKey;

      HttpResponse<String> response = send("GET", path, null, false, header, value, API_CHAIN, "1");

      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            POST   | /who/me/email-addresses               | {"address":"new@example.com"}
            POST   | /who/me/email-addresses/confirmations | {"code":"000000"}
            DELETE | /who/me/email-addresses/{email}       |
            DELETE | /who/me/accounts/{account}            |
            """)
    void a_bearer_token_may_not_do_what_only_a_browser_may(String method, String path, String body)
        throws Exception {
      HttpResponse<String> response =
          send(method, path, body, false, "Authorization", "Bearer " + TOKEN, API_CHAIN, "1");

      assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
      assertThat(emailCredentialService.findAllFor(alice.id())).hasSize(1);
    }
  }
}
