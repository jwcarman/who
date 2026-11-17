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
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code /who/me/sessions} over HTTP: two browsers of one person, one ending the other. */
@SpringBootTest(
    classes = WhoSessionsEndpointsTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.enabled=false",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSessionsEndpointsTest {

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
  @Autowired private JdbcClient jdbcClient;
  @Autowired private EmailCredentialService emailCredentialService;

  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_identity").update();
    Identity identity = whoService.createIdentity();
    emailCredentialService.register(identity, ADDRESS);
    SENT.clear();
  }

  /** A browser with its own cookies. */
  private final class Browser {
    private final CookieManager cookies = new CookieManager();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest.Builder to(String path) {
      return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Firefox/132.0");
    }

    HttpResponse<String> get(String path) throws Exception {
      return send(to(path).GET());
    }

    String csrfToken() throws Exception {
      get("/who/me");
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("XSRF-TOKEN"))
          .map(HttpCookie::getValue)
          .findFirst()
          .orElseThrow();
    }

    HttpResponse<String> post(String path, String body) throws Exception {
      return send(
          to(path)
              .header("Content-Type", "application/json")
              .header("X-XSRF-TOKEN", csrfToken())
              .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    HttpResponse<String> delete(String path) throws Exception {
      return send(to(path).header("X-XSRF-TOKEN", csrfToken()).DELETE());
    }

    Browser signIn() throws Exception {
      assertThat(post("/who/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
          .isLessThan(300);
      String link = SENT.getLast().link();
      String token = link.substring(link.indexOf("#token=") + "#token=".length());
      assertThat(post("/who/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
          .isEqualTo(200);
      return this;
    }

    List<Map<String, Object>> sessions() throws Exception {
      HttpResponse<String> response = get("/who/me/sessions");
      assertThat(response.statusCode()).isEqualTo(200);
      return json.readValue(
          response.body(), json.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    String currentId() throws Exception {
      return sessions().stream()
          .filter(s -> Boolean.TRUE.equals(s.get("current")))
          .map(s -> (String) s.get("id"))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  void a_signed_out_caller_learns_which_methods_are_on() throws Exception {
    HttpResponse<String> response = new Browser().get("/who/sign-in");

    assertThat(response.statusCode()).isEqualTo(200);
    // passkeys are switched off, so nothing offers them; no provider is configured
    assertThat(json.readTree(response.body()))
        .isEqualTo(json.readTree("{\"email\":true,\"passkey\":false,\"providers\":[]}"));
  }

  @Test
  void me_reports_how_the_browser_signed_in_and_until_when_that_is_recent() throws Exception {
    Browser browser = new Browser().signIn();

    HttpResponse<String> response = browser.get("/who/me");

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode me = json.readTree(response.body());
    assertThat(me.get("signInMethod").asString()).isEqualTo("email-link");
    // one vocabulary: the sessions view names the same sign-in the same way
    assertThat(browser.sessions())
        .filteredOn(session -> Boolean.TRUE.equals(session.get("current")))
        .singleElement()
        .satisfies(
            session ->
                assertThat(session.get("signInMethod"))
                    .isEqualTo(me.get("signInMethod").asString()));
    assertThat(Instant.parse(me.get("recentSignInUntil").asString())).isAfter(Instant.now());
    assertThat(me.get("credentials").get("email").asLong()).isEqualTo(1);
  }

  @Test
  void an_ended_session_is_signed_out_on_its_next_request() throws Exception {
    Browser laptop = new Browser().signIn();
    Browser phone = new Browser().signIn();
    String laptopId = laptop.currentId();
    assertThat(phone.sessions()).hasSize(2);

    assertThat(phone.delete("/who/me/sessions/" + laptopId).statusCode()).isEqualTo(204);

    assertThat(laptop.get("/who/me").statusCode()).isEqualTo(401);
    assertThat(phone.get("/who/me").statusCode()).isEqualTo(200);
    assertThat(phone.sessions()).hasSize(1);
  }

  @Test
  void signing_out_removes_the_session_from_the_list() throws Exception {
    Browser laptop = new Browser().signIn();
    Browser phone = new Browser().signIn();
    assertThat(phone.sessions())
        .hasSize(2)
        .allSatisfy(s -> assertThat(s).containsEntry("device", "Firefox on Linux"));

    assertThat(
            laptop
                .send(
                    laptop
                        .to("/who/logout")
                        .header("X-XSRF-TOKEN", laptop.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody()))
                .statusCode())
        .isLessThan(300);

    assertThat(phone.sessions()).hasSize(1);
  }

  @Test
  void ending_every_other_session_keeps_this_one() throws Exception {
    Browser laptop = new Browser().signIn();
    Browser phone = new Browser().signIn();
    Browser tablet = new Browser().signIn();

    HttpResponse<String> ended = phone.delete("/who/me/sessions?others=true");

    assertThat(ended.statusCode()).isEqualTo(200);
    assertThat(json.readTree(ended.body()).get("ended").asInt()).isEqualTo(2);
    assertThat(laptop.get("/who/me").statusCode()).isEqualTo(401);
    assertThat(tablet.get("/who/me").statusCode()).isEqualTo(401);
    assertThat(phone.get("/who/me").statusCode()).isEqualTo(200);
  }
}
