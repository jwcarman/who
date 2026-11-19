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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.jwcarman.who.spring.security.WhoStatelessConfigurer;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link WhoSecurity#stateless()} mounts who's token and key sign-in into a chain of its own: in an
 * app whose web chain signs people in with sessions, the API chain signs in only what a request
 * carries itself, never a session cookie, and answers a signed-out request with {@code 401}.
 */
@SpringBootTest(
    classes = WhoSecurityStatelessTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.rp-id=localhost",
      "who.passkey.allowed-origins=http://localhost",
      "who.security.public-paths=/open/**",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSecurityStatelessTest {

  static final String ISSUER = "https://issuer.example.com";
  static final String GOOD_TOKEN = "good-token";
  static final String SUBJECT = "alice";
  static final String ADDRESS = "alice@example.com";

  /** A permission alice holds and her key was not scoped to. */
  static final String EXTRA = "test.extra";

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
      return WhoSecurityStatelessTest::decode;
    }

    @Bean
    PingController pingController() {
      return new PingController();
    }

    @Bean
    Advice advice() {
      return new Advice();
    }

    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http) {
      http.securityMatcher("/api/**")
          .with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(
              a -> a.requestMatchers("/api/denied").denyAll().anyRequest().authenticated());
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

    @GetMapping({"/api/ping", "/web/ping", "/open/ping"})
    String get(@AuthenticationPrincipal Object principal) {
      return String.valueOf(principal);
    }

    @PostMapping({"/api/ping", "/web/ping"})
    String post(@AuthenticationPrincipal Object principal) {
      return String.valueOf(principal);
    }

    /**
     * Signs the browser in the way plain Spring Security does, under its own session attribute, as
     * an application's own web chain might.
     */
    @GetMapping("/open/spring-session-sign-in")
    String springSessionSignIn(HttpServletRequest request, HttpServletResponse response) {
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated("bob", null, List.of()));
      new HttpSessionSecurityContextRepository().saveContext(context, request, response);
      return "signed in";
    }

    @GetMapping("/api/guarded")
    @RequiresPermission(EXTRA)
    String guarded() {
      return "ok";
    }
  }

  /** Shapes who's signed-out answer, as an application's own advice would. */
  @RestControllerAdvice
  static class Advice {
    @ExceptionHandler(NotSignedInException.class)
    ResponseEntity<String> notSignedIn() {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("advised");
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
    Role extra = rbacService.findRole(EXTRA).orElseGet(() -> rbacService.createRole(EXTRA));
    rbacService.addPermissionToRole(extra, EXTRA);
    rbacService.assignRoleToIdentity(identity, extra);
    apiKey = TestApiKeys.create(apiKeyService, identity, "test");
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

  private HttpRequest.Builder getting(String path) {
    return HttpRequest.newBuilder(uri(path)).GET();
  }

  private HttpRequest.Builder posting(String path) {
    return HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody());
  }

  private String sessionCookie() {
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("SESSION"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private String csrfToken() throws Exception {
    send(getting("/open/ping"));
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private HttpRequest.Builder json(String path, String body) throws Exception {
    return HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/json")
        .header("X-XSRF-TOKEN", csrfToken())
        .POST(HttpRequest.BodyPublishers.ofString(body));
  }

  /** Signs the browser in on the web chain with an emailed link: it now has a session cookie. */
  private void signIn() throws Exception {
    assertThat(send(json("/who/email/send", "{\"email\":\"" + ADDRESS + "\"}")).statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(send(json("/who/email/sign-in", "{\"token\":\"" + token + "\"}")).statusCode())
        .isEqualTo(200);
  }

  @Test
  void a_key_signs_in_on_the_api_chain() throws Exception {
    assertThat(send(getting("/api/ping").header("X-API-Key", apiKey)).statusCode()).isEqualTo(200);
  }

  @Test
  void a_bearer_token_signs_in_on_the_api_chain() throws Exception {
    assertThat(
            send(getting("/api/ping").header("Authorization", "Bearer " + GOOD_TOKEN)).statusCode())
        .isEqualTo(200);
  }

  @Test
  void a_session_cookie_signs_nothing_in_on_the_api_chain() throws Exception {
    signIn();

    assertThat(send(getting("/web/ping")).statusCode()).isEqualTo(200);
    assertThat(send(getting("/api/ping")).statusCode()).isEqualTo(401);
  }

  @Test
  void a_plain_spring_session_signs_nothing_in_on_the_api_chain() throws Exception {
    assertThat(send(getting("/open/spring-session-sign-in")).statusCode()).isEqualTo(200);

    assertThat(send(getting("/api/ping")).statusCode()).isEqualTo(401);
  }

  @Test
  void a_signed_out_request_creates_no_session() throws Exception {
    HttpResponse<String> response = send(getting("/api/ping").header("Accept", "text/html"));

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
  }

  @Test
  void a_key_with_a_session_cookie_leaves_the_session_alone() throws Exception {
    signIn();
    String before = sessionCookie();

    HttpResponse<String> response = send(getting("/api/ping").header("X-API-Key", apiKey));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(sessionCookie()).isEqualTo(before);
    assertThat(send(getting("/web/ping")).statusCode()).isEqualTo(200);
  }

  @Test
  void a_denied_request_is_403_without_a_challenge() throws Exception {
    HttpResponse<String> response = send(getting("/api/denied").header("X-API-Key", apiKey));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
  }

  @Test
  void a_signed_out_post_is_401_not_a_csrf_403() throws Exception {
    signIn();

    assertThat(send(posting("/api/ping")).statusCode()).isEqualTo(401);
  }

  @Test
  void a_key_with_a_session_cookie_has_the_key_scopes_only() throws Exception {
    signIn();

    assertThat(send(getting("/api/guarded").header("X-API-Key", apiKey)).statusCode())
        .isEqualTo(403);
  }

  @Test
  void the_signed_out_401_challenges_with_bearer() throws Exception {
    HttpResponse<String> response = send(getting("/api/ping"));

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
  }

  @Test
  void the_signed_out_401_reaches_the_application_advice() throws Exception {
    HttpResponse<String> response = send(getting("/api/ping"));

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).isEqualTo("advised");
  }

  @Test
  void a_post_with_a_key_needs_no_csrf_token() throws Exception {
    assertThat(send(posting("/api/ping").header("X-API-Key", apiKey)).statusCode()).isEqualTo(200);
  }

  @Test
  void the_api_chain_creates_no_session() throws Exception {
    HttpResponse<String> response = send(getting("/api/ping").header("X-API-Key", apiKey));

    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
  }

  @Test
  void a_bearer_request_creates_no_session() throws Exception {
    long before = sessions();

    HttpResponse<String> response =
        send(getting("/api/ping").header("Authorization", "Bearer " + GOOD_TOKEN));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    assertThat(sessions()).isEqualTo(before);
  }

  private long sessions() {
    return jdbcClient.sql("SELECT count(*) FROM spring_session").query(Long.class).single();
  }

  @Test
  void a_web_post_still_needs_a_csrf_token() throws Exception {
    signIn();

    assertThat(send(posting("/web/ping")).statusCode()).isEqualTo(403);
  }

  private static WebApplicationContextRunner apiOnly() {
    return new WebApplicationContextRunner()
        .withPropertyValues("migratory.enabled=false")
        .withConfiguration(
            WhoTestAutoConfigurations.who(
                SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
        .withUserConfiguration(WhoSecurityAllTest.Database.class, ApiChain.class)
        .withPropertyValues("who.email-link.enabled=false", "who.passkey.enabled=false");
  }

  @Configuration(proxyBeanMethods = false)
  static class ApiChain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoSecurity.stateless(), withDefaults())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Nested
  class With_keys_only {

    @Test
    void the_signed_out_401_has_no_challenge() {
      apiOnly()
          .withPropertyValues("who.jwt.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                mockMvc(context)
                    .perform(get("/anything"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("WWW-Authenticate"));
              });
    }
  }

  @Nested
  class Without_a_matcher {

    @Test
    void logout_is_not_mounted() {
      apiOnly()
          .withPropertyValues("who.jwt.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                mockMvc(context).perform(get("/who/logout")).andExpect(status().isUnauthorized());
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class SessionsAfterwards {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.with(WhoSecurity.stateless(), withDefaults())
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
          .authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Nested
  class With_sessions_turned_back_on {

    @Test
    void startup_fails() {
      new WebApplicationContextRunner()
          .withPropertyValues("migratory.enabled=false")
          .withConfiguration(
              WhoTestAutoConfigurations.who(
                  SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
          .withUserConfiguration(WhoSecurityAllTest.Database.class, SessionsAfterwards.class)
          .withPropertyValues(
              "who.email-link.enabled=false", "who.passkey.enabled=false", "who.jwt.enabled=false")
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .isNotNull()
                      .rootCause()
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage(WhoStatelessConfigurer.STATELESS_REQUIRED));
    }
  }

  /** {@link WhoSecurity#all()} with no browser method answers as {@code stateless()} does. */
  @Nested
  class All_without_a_browser_method {

    @Test
    void the_signed_out_401_challenges_with_bearer() {
      new WebApplicationContextRunner()
          .withPropertyValues("migratory.enabled=false")
          .withConfiguration(
              WhoTestAutoConfigurations.who(
                  SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
          .withUserConfiguration(
              WhoSecurityAllTest.Database.class,
              WhoSecurityAllTest.Decoder.class,
              WhoSecurityAllTest.OwnChain.class)
          .withPropertyValues("who.email-link.enabled=false", "who.passkey.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                mockMvc(context)
                    .perform(get("/anything"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
              });
    }
  }

  private static MockMvc mockMvc(WebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Nested
  class With_no_stateless_method {

    @Test
    void startup_fails_naming_the_modules() {
      apiOnly()
          .withPropertyValues("who.jwt.enabled=false", "who.api-key.enabled=false")
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .isNotNull()
                      .rootCause()
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage(WhoStatelessConfigurer.NO_METHODS));
    }
  }
}
