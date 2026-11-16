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
package org.jwcarman.who.examples.observability;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.apikey.NewApiKey;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The requests the traffic generator makes: real HTTP calls to this app, marked {@code X-Traffic:
 * synthetic}, so each one is a real trace and lands on who's meters as a real sign-in would. Keys
 * belong to {@value Setup#TRAFFIC_EMAIL} and are made through {@link ApiKeyService}; email sign-ins
 * read their code back from Mailpit.
 */
@Component
class TrafficScenarios {

  private static final String XSRF_COOKIE = "XSRF-TOKEN";

  private final RestClient.Builder clients;
  private final ApiKeyService apiKeys;
  private final EmailCredentialService emailCredentials;
  private final WhoService whoService;
  private final Mailpit mailpit;
  private final Clock clock = Clock.systemUTC();

  private volatile String baseUrl;
  private NewApiKey readKey;
  private NewApiKey expiredKey;

  TrafficScenarios(
      RestClient.Builder clients,
      ApiKeyService apiKeys,
      EmailCredentialService emailCredentials,
      WhoService whoService,
      ExampleProperties properties) {
    this.clients = clients;
    this.apiKeys = apiKeys;
    this.emailCredentials = emailCredentials;
    this.whoService = whoService;
    this.mailpit = new Mailpit(clients.clone().baseUrl(properties.mailpitUrl().toString()).build());
  }

  /** Learns the port the app listens on, once the web server has started. */
  @EventListener
  void onStarted(WebServerInitializedEvent event) {
    baseUrl = "http://localhost:" + event.getWebServer().getPort();
  }

  /**
   * Whether the web server has started, so there is something to call.
   *
   * @return true once requests can be made
   */
  boolean ready() {
    return baseUrl != null;
  }

  /**
   * Makes one scenario's requests.
   *
   * @param scenario what to do
   * @return the status of the request that shows the scenario's outcome
   */
  synchronized int run(Scenario scenario) {
    return switch (scenario) {
      case GOOD_KEY -> readGreeting(readKey().key());
      case MISSING_SCOPE -> writeGreeting(readKey().key());
      case UNKNOWN_KEY -> readGreeting("who_not-a-key-who-issued");
      case REVOKED_KEY -> revokedKey();
      case EXPIRED_KEY -> readGreeting(expiredKey().key());
      case CODE_FOR_KNOWN_ADDRESS ->
          new Browser().post("/who/email/send", email(Setup.TRAFFIC_EMAIL));
      case CODE_FOR_UNKNOWN_ADDRESS ->
          new Browser().post("/who/email/send", email("nobody@example.com"));
      case WRONG_CODE -> wrongCode();
      case CODE_SIGN_IN -> codeSignIn();
    };
  }

  private int readGreeting(String key) {
    return api()
        .get()
        .uri("/api/greeting")
        .header("X-API-Key", key)
        .exchange((req, res) -> res.getStatusCode().value());
  }

  private int writeGreeting(String key) {
    return api()
        .post()
        .uri("/api/greeting")
        .header("X-API-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("greeting", "Hello from the traffic generator"))
        .exchange((req, res) -> res.getStatusCode().value());
  }

  private int revokedKey() {
    NewApiKey key =
        apiKeys.create(
            identity(), "revoked", Duration.ofHours(1), Set.of(Permissions.GREETING_READ));
    apiKeys.revoke(key.id());
    return readGreeting(key.key());
  }

  private int wrongCode() {
    Browser browser = new Browser();
    browser.post("/who/email/send", email(Setup.WRONG_CODE_EMAIL));
    return browser.post(
        "/who/email/sign-in", Map.of("email", Setup.WRONG_CODE_EMAIL, "code", "000-000"));
  }

  private int codeSignIn() {
    Browser browser = new Browser();
    browser.post("/who/email/send", email(Setup.TRAFFIC_EMAIL));
    String code =
        mailpit
            .latestCode(Setup.TRAFFIC_EMAIL)
            .orElseThrow(() -> new IllegalStateException("No sign-in email in Mailpit yet"));
    int status =
        browser.post("/who/email/sign-in", Map.of("email", Setup.TRAFFIC_EMAIL, "code", code));
    if (status == 200) {
      browser.get("/who/me");
      browser.post("/who/logout", null);
    }
    return status;
  }

  /** A key that reads the greeting, made again before it expires. */
  private NewApiKey readKey() {
    if (readKey == null
        || readKey.expiresAt().isBefore(clock.instant().plus(Duration.ofHours(1)))) {
      if (readKey != null) {
        apiKeys.revoke(readKey.id());
      }
      readKey =
          apiKeys.create(
              identity(), "traffic", Duration.ofDays(1), Set.of(Permissions.GREETING_READ));
    }
    return readKey;
  }

  /** A key that lived one second; the first call waits for it to expire. */
  private NewApiKey expiredKey() {
    if (expiredKey == null) {
      expiredKey =
          apiKeys.create(
              identity(), "expired", Duration.ofSeconds(1), Set.of(Permissions.GREETING_READ));
    }
    Duration left = Duration.between(clock.instant(), expiredKey.expiresAt());
    if (!left.isNegative()) {
      sleep(left.plusMillis(100));
    }
    return expiredKey;
  }

  private Identity identity() {
    return emailCredentials
        .findByAddress(Setup.TRAFFIC_EMAIL)
        .flatMap(whoService::resolve)
        .map(WhoPrincipal::identity)
        .orElseThrow(() -> new IllegalStateException(Setup.TRAFFIC_EMAIL + " is not set up"));
  }

  private RestClient api() {
    return clients
        .clone()
        .baseUrl(baseUrl)
        .defaultHeader(TrafficObservations.HEADER, TrafficObservations.SYNTHETIC)
        .build();
  }

  private static Map<String, String> email(String address) {
    return Map.of("email", address);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for a key to expire", e);
    }
  }

  /** A browser of its own: cookies, and the CSRF header who's chain asks for on every POST. */
  private final class Browser {

    private final CookieManager cookies = new CookieManager();
    private final RestClient client =
        clients
            .clone()
            .baseUrl(baseUrl)
            .defaultHeader(TrafficObservations.HEADER, TrafficObservations.SYNTHETIC)
            .requestFactory(
                new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().cookieHandler(cookies).build()))
            .build();

    int get(String path) {
      return client.get().uri(path).exchange((req, res) -> res.getStatusCode().value());
    }

    int post(String path, Object body) {
      if (xsrf() == null) {
        // GET /who/me answers 401 signed out, but sets the cookie either way
        get("/who/me");
      }
      RestClient.RequestBodySpec request =
          client
              .post()
              .uri(path)
              .header("X-XSRF-TOKEN", xsrf())
              .contentType(MediaType.APPLICATION_JSON);
      if (body != null) {
        request.body(body);
      }
      return request.exchange((req, res) -> res.getStatusCode().value());
    }

    private String xsrf() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> XSRF_COOKIE.equals(c.getName()))
          .map(HttpCookie::getValue)
          .findFirst()
          .orElse(null);
    }
  }
}
