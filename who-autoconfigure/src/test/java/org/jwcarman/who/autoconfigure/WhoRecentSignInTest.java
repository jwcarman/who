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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.RecentSignInAuthorizationManager;
import org.jwcarman.who.spring.security.RequiresRecentSignIn;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.InsufficientUserAuthenticationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code @RequiresRecentSignIn} and the URL rule over HTTP: browser sessions on a web chain, JWTs
 * and API keys on a stateless one, in an app whose advice answers every {@code
 * AccessDeniedException} with {@code 403}.
 */
@SpringBootTest(
    classes = WhoRecentSignInTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.enabled=false",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoRecentSignInTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String SUBJECT = "alice";
  static final String ADDRESS = "alice@example.com";
  static final String STEP_UP =
      "Bearer error=\"insufficient_user_authentication\", "
          + "error_description=\"A more recent sign-in is required\", max_age=600";

  static final List<EmailLinkMessage> SENT = new CopyOnWriteArrayList<>();
  static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
  }

  /** A clock the tests move forward, so a session can grow old. */
  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    Clock clock() {
      return CLOCK;
    }

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

    /** "recent" signed in a minute ago, "stale" an hour ago, "none" does not say. */
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        Map<String, Object> claims = new HashMap<>(Map.of("iss", ISSUER, "sub", SUBJECT));
        Instant now = CLOCK.instant();
        switch (token) {
          case "recent" -> claims.put("auth_time", now.minusSeconds(60));
          case "stale" -> claims.put("auth_time", now.minusSeconds(3600));
          case "none" -> {
            // no auth_time
          }
          default -> throw new BadJwtException("not a test token");
        }
        return new Jwt(token, now, now.plusSeconds(60), Map.of("alg", "none"), claims);
      };
    }

    @Bean
    Actions actions(Guarded guarded) {
      return new Actions(guarded);
    }

    @Bean
    Guarded guarded() {
      return new Guarded();
    }

    @Bean
    Advice advice() {
      return new Advice();
    }

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, RecentSignInAuthorizationManager recentSignIn) {
      http.securityMatcher("/api/**")
          .with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(
              a -> a.requestMatchers("/api/url").access(recentSignIn).anyRequest().authenticated());
      return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain web(
        HttpSecurity http,
        WhoPublicPaths publicPaths,
        RecentSignInAuthorizationManager recentSignIn) {
      http.with(WhoWebSecurity.passwordless(), withDefaults())
          .authorizeHttpRequests(
              a ->
                  a.requestMatchers(publicPaths.patterns())
                      .permitAll()
                      .requestMatchers("/web/url")
                      .access(recentSignIn)
                      .anyRequest()
                      .authenticated());
      return http.build();
    }
  }

  @RestController
  static class Actions {

    private final Guarded guarded;

    Actions(Guarded guarded) {
      this.guarded = guarded;
    }

    /** Calls a guarded method and, refused, answers something else, as an app may. */
    @GetMapping("/api/recover")
    String recover() {
      try {
        return guarded.make();
      } catch (InsufficientUserAuthenticationException _) {
        return "recovered";
      }
    }

    @GetMapping({"/web/keys", "/api/keys"})
    @RequiresRecentSignIn
    String keys() {
      return "made";
    }

    @GetMapping("/web/quick")
    @RequiresRecentSignIn(within = "1m")
    String quick() {
      return "quick";
    }

    @GetMapping({"/web/plain", "/api/plain", "/web/url", "/api/url"})
    String plain() {
      return "plain";
    }
  }

  /** A service method that needs a recent sign-in. */
  static class Guarded {
    @RequiresRecentSignIn
    String make() {
      return "made";
    }
  }

  /** Answers every access denial with 403, as many applications do. */
  @RestControllerAdvice
  static class Advice {
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<String> denied(AccessDeniedException e) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body("advised: " + e.getMessage());
    }
  }

  @LocalServerPort private int port;
  @Autowired private WhoService whoService;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private RbacService rbacService;
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
    TestApiKeys.grant(rbacService, identity);
    apiKey = TestApiKeys.create(apiKeyService, identity, "test");
    federatedCredentialService.link(identity, ISSUER, SUBJECT);
    emailCredentialService.register(identity, ADDRESS);
    SENT.clear();
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder getting(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(getting(path));
  }

  private HttpResponse<String> bearer(String path, String token) throws Exception {
    return send(getting(path).header("Authorization", "Bearer " + token));
  }

  private String csrfToken() throws Exception {
    send(getting("/who/me"));
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<String> json(String path, String body) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", csrfToken())
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  /** Signs the browser in with an emailed link, at the clock's current time. */
  private void signIn() throws Exception {
    assertThat(json("/who/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(json("/who/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
        .isEqualTo(200);
  }

  @Test
  void a_recent_session_may_and_a_stale_one_must_sign_in_again() throws Exception {
    signIn();
    assertThat(get("/web/keys").statusCode()).isEqualTo(200);

    CLOCK.advance(Duration.ofMinutes(11));

    // 401, though the advice answers every AccessDeniedException with 403
    assertThat(get("/web/keys").statusCode()).isEqualTo(401);
    assertThat(get("/web/plain").statusCode()).isEqualTo(200);

    signIn();
    assertThat(get("/web/keys").statusCode()).isEqualTo(200);
  }

  @Test
  void within_overrides_the_window() throws Exception {
    signIn();
    CLOCK.advance(Duration.ofMinutes(2));

    assertThat(get("/web/quick").statusCode()).isEqualTo(401);
    assertThat(get("/web/keys").statusCode()).isEqualTo(200);
  }

  @Test
  void a_url_rule_answers_a_session_the_same() throws Exception {
    signIn();
    assertThat(get("/web/url").statusCode()).isEqualTo(200);

    CLOCK.advance(Duration.ofMinutes(11));

    assertThat(get("/web/url").statusCode()).isEqualTo(401);
  }

  @Test
  void signed_out_is_the_usual_401() throws Exception {
    assertThat(get("/web/keys").statusCode()).isEqualTo(401);
    HttpResponse<String> api = get("/api/keys");
    assertThat(api.statusCode()).isEqualTo(401);
    assertThat(api.headers().allValues("WWW-Authenticate")).doesNotContain(STEP_UP);
  }

  @Test
  void a_recent_jwt_may() throws Exception {
    assertThat(bearer("/api/keys", "recent").statusCode()).isEqualTo(200);
    assertThat(bearer("/api/url", "recent").statusCode()).isEqualTo(200);
  }

  @Test
  void a_stale_jwt_gets_the_challenge() throws Exception {
    HttpResponse<String> annotated = bearer("/api/keys", "stale");
    assertThat(annotated.statusCode()).isEqualTo(401);
    assertThat(annotated.headers().allValues("WWW-Authenticate")).containsExactly(STEP_UP);

    HttpResponse<String> url = bearer("/api/url", "stale");
    assertThat(url.statusCode()).isEqualTo(401);
    assertThat(url.headers().allValues("WWW-Authenticate")).containsExactly(STEP_UP);

    assertThat(bearer("/api/plain", "stale").statusCode()).isEqualTo(200);
  }

  @Test
  void a_refusal_the_app_recovers_from_sends_no_challenge() throws Exception {
    HttpResponse<String> recovered = bearer("/api/recover", "stale");

    assertThat(recovered.statusCode()).isEqualTo(200);
    assertThat(recovered.body()).isEqualTo("recovered");
    assertThat(recovered.headers().allValues("WWW-Authenticate")).isEmpty();
  }

  @Test
  void a_jwt_without_auth_time_is_403_on_annotated_endpoints_only() throws Exception {
    HttpResponse<String> annotated = bearer("/api/keys", "none");
    assertThat(annotated.statusCode()).isEqualTo(403);
    assertThat(annotated.body()).contains("auth_time");
    assertThat(bearer("/api/url", "none").statusCode()).isEqualTo(403);
    assertThat(bearer("/api/plain", "none").statusCode()).isEqualTo(200);
  }

  @Test
  void an_api_key_is_403_on_annotated_endpoints_only() throws Exception {
    HttpResponse<String> annotated = send(getting("/api/keys").header("X-API-Key", apiKey));
    assertThat(annotated.statusCode()).isEqualTo(403);
    assertThat(annotated.body()).contains("API key");
    assertThat(send(getting("/api/url").header("X-API-Key", apiKey)).statusCode()).isEqualTo(403);
    assertThat(send(getting("/api/plain").header("X-API-Key", apiKey)).statusCode()).isEqualTo(200);
  }
}
