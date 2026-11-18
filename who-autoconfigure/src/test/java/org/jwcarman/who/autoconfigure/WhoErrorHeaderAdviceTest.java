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

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * An application that answers who's failures with its own {@code ProblemDetail}: its body is sent,
 * and who's {@code Who-Error} header stands beside it.
 */
@SpringBootTest(
    classes = {WhoBasePathEndToEndTest.App.class, WhoErrorHeaderAdviceTest.Advice.class},
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
class WhoErrorHeaderAdviceTest {

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    WhoBasePathEndToEndTest.configureProperties(registry);
  }

  /** The application's own error answers. */
  @RestControllerAdvice
  static class Advice {
    @ExceptionHandler(NotSignedInException.class)
    ProblemDetail notSignedIn() {
      return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in first");
    }
  }

  @LocalServerPort private int port;

  private final CookieManager cookies = new CookieManager();
  private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  @Test
  void on_the_applications_paths_its_body_and_whos_header_both_arrive() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(uri("/app/orders")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("\"detail\":\"Sign in first\"");
    assertThat(response.headers().firstValue("Who-Error")).contains("not-signed-in");
  }

  @Test
  void under_whos_base_path_who_answers_whatever_the_advice() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(uri("/auth/me")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    assertThat(response.body())
        .contains("\"code\":\"not-signed-in\"")
        .doesNotContain("Sign in first");
    assertThat(response.headers().firstValue("Who-Error")).contains("not-signed-in");
  }
}
