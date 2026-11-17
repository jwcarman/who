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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.test.StubOidcProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every who endpoint under a root other than {@code /who}, with every sign-in method on: each
 * answers there, none answers at its old path, and an application endpoint under the same root
 * still needs a sign-in.
 */
@SpringBootTest(
    classes = WhoBasePathEndToEndTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.base-path=/auth",
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
class WhoBasePathEndToEndTest {

  static final StubOidcProvider PROVIDER = StubOidcProvider.start();
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
    EmailLinkSender emailLinkSender() {
      return email -> {
        if (email instanceof EmailLinkMessage message) {
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
    HelloController helloController() {
      return new HelloController();
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

  /** The application's own endpoint, under who's root. */
  @RestController
  static class HelloController {
    @GetMapping("/auth/hello")
    String hello() {
      return "hello";
    }
  }

  @LocalServerPort private int port;
  @Autowired private WhoService whoService;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private FederatedCredentialService federatedCredentialService;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    jdbcClient.sql("DELETE FROM who_federated_credential").update();
    Identity identity = whoService.createIdentity();
    emailCredentialService.register(identity, ADDRESS);
    federatedCredentialService.link(identity, ISSUER, SUBJECT);
    SENT.clear();
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(
                method,
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (!"GET".equals(method)) {
      request.header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken());
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String csrfToken() throws Exception {
    client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/me")).GET().build(),
        HttpResponse.BodyHandlers.discarding());
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private void signIn() throws Exception {
    assertThat(send("POST", "/auth/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(send("POST", "/auth/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
        .isEqualTo(200);
    SENT.clear();
  }

  /**
   * Each row answers with a status an unknown path would not: signed out, an unknown path is 401;
   * signed in, 404.
   */
  @ParameterizedTest(name = "{0} {1} (signed in: {2}) is {4}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          POST   | /auth/email/send              | false | {"email":"bob@example.com"} | 202
          GET    | /auth/oidc/authorize/stub     | false |                             | 302
          GET    | /auth/oidc/callback/stub?error=access_denied&state=x | false |    | 302
          GET    | /auth/oidc/pending            | false |                             | 404
          POST   | /auth/oidc/pending/sign-up            | false |                             | 404
          POST   | /auth/passkey/sign-in/options | false |                             | 200
          POST   | /auth/email/sign-in           | true  | {"token":"nope"}            | 401
          POST   | /auth/passkey/sign-in         | true  | {}                          | 401
          GET    | /auth/me                      | true  |                             | 200
          GET    | /auth/me/sessions                | true  |                             | 200
          DELETE | /auth/me/sessions?others=true    | true  |                             | 200
          POST   | /auth/me/email-addresses         | true  | {"email":"new@example.com"} | 202
          POST   | /auth/me/passkeys/options | true |                             | 200
          POST   | /auth/me/passkeys        | true  | {}                          | 400
          GET    | /auth/me/passkeys        | true  |                             | 200
          DELETE | /auth/me/passkeys/AAAA   | true  |                             | 404
          POST   | /auth/me/email-addresses/confirmations | true  | {"token":"nope"}            | 400
          POST   | /auth/me/accounts/stub          | true  |                             | 302
          POST   | /auth/logout                  | true  |                             | 204
          """)
  void every_endpoint_answers_under_the_root(
      String method, String path, boolean signedIn, String body, int status) throws Exception {
    if (signedIn) {
      signIn();
    }

    HttpResponse<String> response = send(method, path, body == null ? "" : body);

    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          POST | /logout                       |
          POST | /ott/generate                 | {"email":"alice@example.com"}
          POST | /login/ott                    | {"token":"nope"}
          POST | /webauthn/register/options    |
          POST | /webauthn/register            | {}
          POST | /webauthn/authenticate/options |
          POST | /login/webauthn               | {}
          GET  | /oauth2/authorization/stub    |
          GET  | /login/oauth2/code/stub       |
          DELETE | /webauthn/register/AAAA     |
          GET  | /who/me                       |
          GET  | /who/me/sessions                 |
          GET  | /who/me/passkeys                |
          DELETE | /who/me/passkeys/AAAA         |
          POST | /who/me/email-addresses          | {"email":"bob@example.com"}
          GET  | /who/oidc/pending             |
          GET  | /who/me/accounts/stub           |
          """)
  void no_old_path_reaches_who(String method, String path, String body) throws Exception {
    signIn();

    HttpResponse<String> response = send(method, path, body == null ? "" : body);

    // a signed-in request to a path nothing serves: a filter still at an old path would answer
    // 400 or 401 instead
    assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    assertThat(response.headers().firstValue("Location")).isEmpty();
    assertThat(SENT).isEmpty();
    // still signed in: the old sign-out did nothing
    assertThat(send("GET", "/auth/me", "").statusCode()).isEqualTo(200);
  }

  /** Spring Boot's own error handling (no advice): the failure still names itself. */
  @Test
  void a_failure_names_itself_in_the_header() throws Exception {
    HttpResponse<String> signedOut =
        send("POST", "/auth/me/email-addresses", "{\"address\":\"x@example.com\"}");
    assertThat(signedOut.statusCode()).isEqualTo(401);
    assertThat(signedOut.headers().firstValue("Who-Error")).contains("not-signed-in");

    signIn();
    HttpResponse<String> badCode =
        send("POST", "/auth/me/email-addresses/confirmations", "{\"code\":\"000000\"}");
    assertThat(badCode.statusCode()).isEqualTo(400);
    assertThat(badCode.headers().firstValue("Who-Error")).contains("invalid-confirmation");
  }

  @Test
  void ending_one_s_own_session_answers_under_the_root() throws Exception {
    signIn();
    Matcher current =
        Pattern.compile("\"id\":\"([^\"]+)\"[^}]*\"current\":true")
            .matcher(send("GET", "/auth/me/sessions", "").body());
    assertThat(current.find()).isTrue();

    assertThat(send("DELETE", "/auth/me/sessions/" + current.group(1), "").statusCode())
        .isEqualTo(204);
    assertThat(send("GET", "/auth/me", "").statusCode()).isEqualTo(401);
  }

  @Test
  void an_application_endpoint_under_the_root_still_needs_sign_in() throws Exception {
    assertThat(send("GET", "/auth/hello", "").statusCode()).isEqualTo(401);

    HttpResponse<String> bearer =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/hello"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(bearer.statusCode()).isEqualTo(200);
    assertThat(bearer.body()).isEqualTo("hello");
  }
}
