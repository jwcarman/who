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
package org.jwcarman.who.turnstile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.HumanCheckRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link TurnstileHumanCheck} over real HTTP, with a real client and real timeouts, against a local
 * stand-in for siteverify that can answer slowly, with an error, or not at all.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnstileHumanCheckHttpTest {

  private static final String SECRET = "0x4AAAAAAAsecret";
  private static final Duration TIMEOUT = Duration.ofMillis(300);
  private static final Duration WITHIN = Duration.ofSeconds(2);
  private static final String GOOD = "{\"success\":true,\"action\":\"email-send\"}";

  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicReference<String> received = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private HttpServer server;
  private volatile int status = 200;
  private volatile String answer = GOOD;
  private volatile boolean slow;

  @BeforeEach
  void startSiteverify() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/siteverify",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          if (slow) {
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] body = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopSiteverify() {
    release.countDown();
    server.stop(0);
  }

  private URI verifyUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/siteverify");
  }

  private static TurnstileHumanCheck check(URI verifyUrl, boolean failOpen) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(TIMEOUT);
    requestFactory.setReadTimeout(TIMEOUT);
    return new TurnstileHumanCheck(
        RestClient.builder().requestFactory(requestFactory).build(),
        verifyUrl,
        SECRET,
        TurnstileVerifier.strict(Set.of()),
        failOpen);
  }

  private static boolean passes(TurnstileHumanCheck check, String token) {
    return assertTimeoutPreemptively(
        WITHIN,
        () -> check.passes(new HumanCheckRequest(Optional.of(token), "203.0.113.7", "email-send")));
  }

  @Test
  void passes_and_sends_the_form_encoded() {
    assertThat(passes(check(verifyUrl(), false), "a+b/c=d&e")).isTrue();

    assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
    assertThat(received.get())
        .isEqualTo("secret=0x4AAAAAAAsecret&response=a%2Bb%2Fc%3Dd%26e&remoteip=203.0.113.7");
  }

  @Test
  void a_refusal_fails() {
    answer = "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}";

    assertThat(passes(check(verifyUrl(), false), "tok")).isFalse();
  }

  @Test
  void a_refusal_fails_even_failing_open() {
    answer = "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}";

    assertThat(passes(check(verifyUrl(), true), "tok")).isFalse();
  }

  @Test
  void a_slow_answer_fails_within_the_timeout() {
    slow = true;

    assertThat(passes(check(verifyUrl(), false), "tok")).isFalse();
  }

  @Test
  void a_slow_answer_passes_failing_open() {
    slow = true;

    assertThat(passes(check(verifyUrl(), true), "tok")).isTrue();
  }

  @Test
  void a_server_error_fails() {
    status = 500;
    answer = "{}";

    assertThat(passes(check(verifyUrl(), false), "tok")).isFalse();
  }

  @Test
  void a_server_error_passes_failing_open() {
    status = 503;
    answer = "{}";

    assertThat(passes(check(verifyUrl(), true), "tok")).isTrue();
  }

  @Test
  void an_unreachable_server_fails() {
    URI closed = verifyUrl();
    server.stop(0);

    assertThat(passes(check(closed, false), "tok")).isFalse();
  }

  @Test
  void an_unreachable_server_passes_failing_open() {
    URI closed = verifyUrl();
    server.stop(0);

    assertThat(passes(check(closed, true), "tok")).isTrue();
  }

  @Test
  void a_success_for_another_action_fails() {
    answer = "{\"success\":true,\"action\":\"email-sign-in\"}";

    assertThat(passes(check(verifyUrl(), false), "tok")).isFalse();
  }
}
