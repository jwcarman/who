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
import java.util.List;
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
import org.jwcarman.who.emaillink.WhoEmailSender;
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

/** who's own endpoints and sign-out, under a root other than {@code /who}. */
@SpringBootTest(
    classes = WhoOwnEndpointsRootTest.App.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "who.base-path=/auth",
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.enabled=false",
      "spring.session.jdbc.initialize-schema=always"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoOwnEndpointsRootTest {

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
    WhoEmailSender whoEmailSender() {
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

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

  @BeforeEach
  void setUp() throws Exception {
    jdbcClient.sql("DELETE FROM who_identity").update();
    Identity identity = whoService.createIdentity();
    emailCredentialService.register(identity, ADDRESS);
    SENT.clear();
    signIn();
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder to(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(to(path).GET());
  }

  private String csrfToken() throws Exception {
    get("/auth/me");
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals("XSRF-TOKEN"))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return send(
        to(path)
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", csrfToken())
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private void signIn() throws Exception {
    assertThat(post("/auth/email/send", "{\"email\":\"" + ADDRESS + "\"}").statusCode())
        .isLessThan(300);
    String link = SENT.getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(post("/auth/email/sign-in", "{\"token\":\"" + token + "\"}").statusCode())
        .isEqualTo(200);
  }

  @Test
  void me_answers_under_the_root() throws Exception {
    assertThat(get("/auth/me").statusCode()).isEqualTo(200);
  }

  @Test
  void sessions_answer_under_the_root() throws Exception {
    HttpResponse<String> sessions = get("/auth/me/sessions");

    assertThat(sessions.statusCode()).isEqualTo(200);
    assertThat(sessions.body()).startsWith("[");
  }

  @Test
  void sign_out_answers_under_the_root() throws Exception {
    assertThat(post("/auth/logout", "").statusCode()).isEqualTo(204);

    assertThat(get("/auth/me").statusCode()).isEqualTo(401);
  }

  @Test
  void the_old_paths_reach_no_who_endpoint() throws Exception {
    assertThat(get("/who/me").statusCode()).isNotEqualTo(200);
    assertThat(get("/who/me/sessions").statusCode()).isNotEqualTo(200);

    post("/logout", "");

    assertThat(get("/auth/me").statusCode()).isEqualTo(200);
  }
}
