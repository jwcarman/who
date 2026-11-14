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
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.WhoApiKeySecurity;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.RecordingSender;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * API keys on a real servlet container, where an error answer is forwarded to {@code /error} and
 * that ERROR dispatch runs through the security chain again, with the same API key header. Each
 * request signs in once: a refused key is refused once, with the answer an email-link failure gets,
 * and a key that signs in keeps its identity on the error page without signing in again.
 */
@SpringBootTest(
    classes = ApiKeyErrorDispatchTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyErrorDispatchTest {

  private static final String HEADER = "X-API-Key";

  /** Every event who published, in order. */
  static final List<WhoEvent> EVENTS = new CopyOnWriteArrayList<>();

  /** Every authentication Spring reported as successful, in order. */
  static final List<AuthenticationSuccessEvent> SPRING_SUCCESSES = new CopyOnWriteArrayList<>();

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
    WhoEventPublisher whoEventPublisher() {
      return EVENTS::add;
    }

    @Bean
    AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher events) {
      return new DefaultAuthenticationEventPublisher(events);
    }

    @Bean
    ApplicationListener<AuthenticationSuccessEvent> springSuccesses() {
      return SPRING_SUCCESSES::add;
    }

    @Bean
    MutableClock clock() {
      return new MutableClock(Instant.parse("2026-11-13T10:00:00Z"));
    }

    @Bean
    RecordingSender recordingSender() {
      return new RecordingSender();
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    /** Adds who the error page sees the request as, so the tests can tell. */
    @Bean
    ErrorAttributes errorAttributes() {
      return new DefaultErrorAttributes() {
        @Override
        public Map<String, Object> getErrorAttributes(
            WebRequest webRequest, ErrorAttributeOptions options) {
          Map<String, Object> attributes = super.getErrorAttributes(webRequest, options);
          attributes.put("who", currentName());
          return attributes;
        }
      };
    }

    @Bean
    PingController pingController() {
      return new PingController();
    }

    /** One chain for everything, the error page included, as many applications have. */
    @Bean
    SecurityFilterChain chain(HttpSecurity http) {
      http.authorizeHttpRequests(
              a -> a.requestMatchers("/open/**", "/error").permitAll().anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults())
          .with(WhoApiKeySecurity.apiKey(), withDefaults());
      return http.build();
    }
  }

  static String currentName() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || authentication instanceof AnonymousAuthenticationToken
        ? "anonymous"
        : authentication.getName();
  }

  @RestController
  static class PingController {

    @GetMapping({"/open/ping", "/secure/ping"})
    String ping() {
      return currentName();
    }
  }

  @LocalServerPort private int port;
  @Autowired private WhoService whoService;
  @Autowired private ApiKeyService apiKeyService;

  private final HttpClient client = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void clearEvents() {
    EVENTS.clear();
  }

  private HttpResponse<String> get(String path, String key) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header(HEADER, key)
            .header("Accept", "application/json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A browser: fetches the CSRF cookie, then posts a bogus sign-in link token. */
  private HttpResponse<String> failEmailLinkSignIn() throws Exception {
    CookieManager cookies = new CookieManager();
    HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    browser.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/open/ping")).build(),
        HttpResponse.BodyHandlers.ofString());
    String csrf =
        cookies.getCookieStore().getCookies().stream()
            .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
            .map(HttpCookie::getValue)
            .findFirst()
            .orElseThrow();
    return browser.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login/ott"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-XSRF-TOKEN", csrf)
            .POST(HttpRequest.BodyPublishers.ofString("{\"token\":\"bogus\"}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The answer without what differs between any two requests (when and where). */
  private JsonNode comparable(HttpResponse<String> response) {
    ObjectNode body = (ObjectNode) json.readTree(response.body());
    body.remove(List.of("timestamp", "path"));
    return body;
  }

  private List<WhoEvent> apiKeyEvents() {
    return EVENTS.stream()
        .filter(
            event ->
                event instanceof SignInFailed failed && failed.method() == SignInMethod.API_KEY)
        .toList();
  }

  @Nested
  class A_refused_key {

    @Test
    void signs_in_once_and_gets_the_answer_an_email_link_failure_gets() throws Exception {
      HttpResponse<String> refused = get("/open/ping", "who_unknown");

      assertThat(refused.statusCode()).isEqualTo(401);
      assertThat(apiKeyEvents()).singleElement().isInstanceOf(SignInFailed.class);

      HttpResponse<String> emailLink = failEmailLinkSignIn();
      assertThat(emailLink.statusCode()).isEqualTo(401);
      assertThat(comparable(refused)).isEqualTo(comparable(emailLink));
    }
  }

  @Nested
  class A_key_that_signs_in {

    @Test
    void keeps_its_identity_on_the_error_page_and_signs_in_once() throws Exception {
      Identity identity = whoService.createIdentity();
      String key = apiKeyService.create(identity, "error-dispatch");
      EVENTS.clear();
      SPRING_SUCCESSES.clear();

      HttpResponse<String> missing = get("/secure/missing", key);

      assertThat(missing.statusCode()).isEqualTo(404);
      assertThat(json.readTree(missing.body()).path("who").asString())
          .isEqualTo(identity.id().toString());
      // a valid key publishes no who event; Spring reports the one authentication
      assertThat(apiKeyEvents()).isEmpty();
      assertThat(SPRING_SUCCESSES)
          .singleElement()
          .satisfies(
              success ->
                  assertThat(success.getAuthentication())
                      .isInstanceOfSatisfying(
                          WhoAuthenticationToken.class,
                          token ->
                              assertThat(token.signInMethod()).hasValue(SignInMethod.API_KEY)));
    }
  }
}
