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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.spring.security.HumanCheckRequest;
import org.jwcarman.who.spring.security.WhoHumanCheck;
import org.jwcarman.who.turnstile.TurnstileHumanCheck;
import org.jwcarman.who.turnstile.TurnstileVerifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(OutputCaptureExtension.class)
class WhoTurnstileAutoConfigurationTest {

  private static final String SECRET = "who.turnstile.secret-key=0x4AAAAAAAsecret";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WhoTurnstileAutoConfiguration.class))
          // this module's surefire turns Turnstile off for every other test
          .withPropertyValues("who.turnstile.enabled=true");

  @Test
  void the_jar_and_a_secret_give_a_turnstile_check() {
    runner
        .withPropertyValues(SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WhoHumanCheck.class))
                  .isInstanceOf(TurnstileHumanCheck.class);
            });
  }

  @Test
  void a_missing_secret_fails_startup_naming_the_property() {
    runner.run(WhoTurnstileAutoConfigurationTest::assertSecretRefused);
  }

  @Test
  void a_blank_secret_fails_startup() {
    runner
        .withPropertyValues("who.turnstile.secret-key= ")
        .run(WhoTurnstileAutoConfigurationTest::assertSecretRefused);
  }

  @Test
  void an_unresolved_placeholder_secret_fails_startup() {
    runner
        .withPropertyValues("who.turnstile.secret-key=${TURNSTILE_SECRET_NOT_SET}")
        .run(WhoTurnstileAutoConfigurationTest::assertSecretRefused);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-1s"})
  void a_timeout_that_is_not_positive_fails_startup(String timeout) {
    runner
        .withPropertyValues(SECRET, "who.turnstile.timeout=" + timeout)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOfSatisfying(
                        WhoConfigurationException.class,
                        e -> assertThat(e.property()).isEqualTo("who.turnstile.timeout")));
  }

  /**
   * A local siteverify that answers after {@code delay}, saying the token is good for email-send.
   */
  private static HttpServer siteverify(Duration delay) throws IOException {
    return siteverify(delay, "{\"success\":true,\"action\":\"email-send\"}");
  }

  private static HttpServer siteverify(Duration delay, String answer) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/siteverify",
        exchange -> {
          try {
            Thread.sleep(delay);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = answer.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static String verifyUrl(HttpServer server) {
    return "who.turnstile.verify-url=http://127.0.0.1:"
        + server.getAddress().getPort()
        + "/siteverify";
  }

  private static final HumanCheckRequest SEND =
      new HumanCheckRequest(Optional.of("tok"), "203.0.113.7", "email-send");

  @Test
  void the_check_asks_the_configured_verify_url() throws IOException {
    HttpServer server = siteverify(Duration.ZERO);
    try {
      runner
          .withPropertyValues(SECRET, verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isTrue());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void the_configured_timeout_bounds_the_wait() throws IOException {
    HttpServer server = siteverify(Duration.ofSeconds(2));
    try {
      runner
          .withPropertyValues(SECRET, verifyUrl(server), "who.turnstile.timeout=200ms")
          .run(
              context -> {
                WhoHumanCheck check = context.getBean(WhoHumanCheck.class);
                assertThat(
                        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> check.passes(SEND)))
                    .isFalse();
              });
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"not a url", "/siteverify", "ftp://example.com/siteverify"})
  void a_verify_url_that_is_not_http_fails_startup(String url) {
    runner
        .withPropertyValues(SECRET, "who.turnstile.verify-url=" + url)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOfSatisfying(
                        WhoConfigurationException.class,
                        e -> assertThat(e.property()).isEqualTo("who.turnstile.verify-url")));
  }

  @Test
  void a_cloudflare_test_secret_is_warned_about(CapturedOutput output) {
    runner
        .withPropertyValues("who.turnstile.secret-key=1x0000000000000000000000000000000AA")
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(output).contains("who.turnstile.secret-key is one of Cloudflare's test keys");
  }

  @Test
  void a_real_secret_is_not_warned_about(CapturedOutput output) {
    runner.withPropertyValues(SECRET).run(context -> assertThat(context).hasNotFailed());

    assertThat(output).doesNotContain("Cloudflare's test keys");
  }

  /** Cloudflare's answer for a test key: success, no action, hostname example.com. */
  private static final String TEST_KEY_ANSWER = "{\"success\":true,\"hostname\":\"example.com\"}";

  @Test
  void a_cloudflare_test_secret_passes_on_success_alone() throws IOException {
    HttpServer server = siteverify(Duration.ZERO, TEST_KEY_ANSWER);
    try {
      runner
          .withPropertyValues(
              "who.turnstile.secret-key=1x0000000000000000000000000000000AA",
              "who.turnstile.hostnames=localhost",
              verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isTrue());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void a_real_secret_needs_the_action() throws IOException {
    HttpServer server = siteverify(Duration.ZERO, TEST_KEY_ANSWER);
    try {
      runner
          .withPropertyValues(SECRET, verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isFalse());
    } finally {
      server.stop(0);
    }
  }

  static final AtomicInteger INTERCEPTED = new AtomicInteger();

  @Configuration(proxyBeanMethods = false)
  static class ApplicationBuilder {
    @Bean
    RestClient.Builder restClientBuilder() {
      return RestClient.builder()
          .requestInterceptor(
              (request, body, execution) -> {
                INTERCEPTED.incrementAndGet();
                return execution.execute(request, body);
              });
    }
  }

  @Test
  void the_applications_rest_client_builder_is_used() throws IOException {
    INTERCEPTED.set(0);
    HttpServer server = siteverify(Duration.ZERO);
    try {
      runner
          .withUserConfiguration(ApplicationBuilder.class)
          .withPropertyValues(SECRET, verifyUrl(server))
          .run(
              context -> {
                assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isTrue();
                assertThat(INTERCEPTED).hasValue(1);
              });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void the_timeout_holds_with_the_applications_builder() throws IOException {
    HttpServer server = siteverify(Duration.ofSeconds(2));
    try {
      runner
          .withUserConfiguration(ApplicationBuilder.class)
          .withPropertyValues(SECRET, verifyUrl(server), "who.turnstile.timeout=200ms")
          .run(
              context -> {
                WhoHumanCheck check = context.getBean(WhoHumanCheck.class);
                assertThat(
                        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> check.passes(SEND)))
                    .isFalse();
              });
    } finally {
      server.stop(0);
    }
  }

  /** Answers 302 to /siteverify, sending the client to /moved, which says the token is good. */
  private static HttpServer redirecting() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/siteverify",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Location", "/moved");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/moved",
        exchange -> {
          byte[] body =
              "{\"success\":true,\"action\":\"email-send\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return server;
  }

  @Configuration(proxyBeanMethods = false)
  static class NoRedirects {
    @Bean
    HttpClientSettings httpClientSettings() {
      return HttpClientSettings.defaults().withRedirects(HttpRedirects.DONT_FOLLOW);
    }
  }

  @Test
  void by_default_the_client_follows_redirects() throws IOException {
    HttpServer server = redirecting();
    try {
      runner
          .withPropertyValues(SECRET, verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isTrue());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void the_applications_http_client_settings_are_used() throws IOException {
    HttpServer server = redirecting();
    try {
      runner
          .withUserConfiguration(NoRedirects.class)
          .withPropertyValues(SECRET, verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isFalse());
    } finally {
      server.stop(0);
    }
  }

  static final List<HttpClientSettings> BUILT_WITH = new CopyOnWriteArrayList<>();

  @Configuration(proxyBeanMethods = false)
  static class RecordingFactoryBuilder {
    @Bean
    HttpClientSettings httpClientSettings() {
      return HttpClientSettings.defaults()
          .withRedirects(HttpRedirects.DONT_FOLLOW)
          .withTimeouts(Duration.ofMinutes(5), Duration.ofMinutes(5));
    }

    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
      return settings -> {
        BUILT_WITH.add(settings);
        return ClientHttpRequestFactoryBuilder.simple().build(settings);
      };
    }
  }

  @Test
  void the_applications_factory_builder_gets_its_settings_with_whos_timeouts() {
    BUILT_WITH.clear();
    runner
        .withUserConfiguration(RecordingFactoryBuilder.class)
        .withPropertyValues(SECRET, "who.turnstile.timeout=750ms")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(BUILT_WITH)
                  .singleElement()
                  .isEqualTo(
                      HttpClientSettings.defaults()
                          .withRedirects(HttpRedirects.DONT_FOLLOW)
                          .withTimeouts(Duration.ofMillis(750), Duration.ofMillis(750)));
            });
  }

  @Test
  void without_spring_boots_http_client_the_timeout_still_holds() throws IOException {
    HttpServer server = siteverify(Duration.ofSeconds(2));
    try {
      runner
          .withClassLoader(new FilteredClassLoader(ClientHttpRequestFactoryBuilder.class))
          .withPropertyValues(SECRET, verifyUrl(server), "who.turnstile.timeout=200ms")
          .run(
              context -> {
                WhoHumanCheck check = context.getBean(WhoHumanCheck.class);
                assertThat(
                        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> check.passes(SEND)))
                    .isFalse();
              });
    } finally {
      server.stop(0);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RefusingVerifier {
    @Bean
    TurnstileVerifier refusingVerifier() {
      return (answer, request) -> false;
    }
  }

  @Test
  void an_application_verifier_decides() throws IOException {
    HttpServer server = siteverify(Duration.ZERO);
    try {
      runner
          .withUserConfiguration(RefusingVerifier.class)
          .withPropertyValues(SECRET, verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isFalse());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void an_application_verifier_is_used_even_with_a_test_secret() throws IOException {
    HttpServer server = siteverify(Duration.ZERO, TEST_KEY_ANSWER);
    try {
      runner
          .withUserConfiguration(RefusingVerifier.class)
          .withPropertyValues(
              "who.turnstile.secret-key=1x0000000000000000000000000000000AA", verifyUrl(server))
          .run(context -> assertThat(context.getBean(WhoHumanCheck.class).passes(SEND)).isFalse());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void enabled_false_turns_it_off() {
    runner
        .withPropertyValues("who.turnstile.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(WhoHumanCheck.class);
            });
  }

  @Test
  void without_the_jar_there_is_no_check() {
    runner
        .withClassLoader(new FilteredClassLoader(TurnstileHumanCheck.class))
        .withPropertyValues(SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(WhoHumanCheck.class);
            });
  }

  @Configuration
  static class OwnCheck {
    @Bean
    WhoHumanCheck ownCheck() {
      return WhoHumanCheck.ALLOW_ALL;
    }
  }

  @Test
  void an_application_check_wins() {
    runner
        .withUserConfiguration(OwnCheck.class)
        .withPropertyValues(SECRET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(WhoHumanCheck.class)).isSameAs(WhoHumanCheck.ALLOW_ALL);
            });
  }

  private static void assertSecretRefused(AssertableApplicationContext context) {
    assertThat(context.getStartupFailure())
        .isNotNull()
        .rootCause()
        .isInstanceOfSatisfying(
            WhoConfigurationException.class,
            e -> {
              assertThat(e.property()).isEqualTo("who.turnstile.secret-key");
              assertThat(e.action()).contains("who.turnstile.enabled=false");
            });
  }
}
