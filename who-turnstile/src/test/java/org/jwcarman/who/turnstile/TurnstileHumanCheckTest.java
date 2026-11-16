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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.HumanCheckRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnstileHumanCheckTest {

  private static final String SECRET = "0x4AAAAAAAsecret";
  private static final HumanCheckRequest SEND =
      new HumanCheckRequest(Optional.of("tok"), "203.0.113.7", "email-send");

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer cloudflare = MockRestServiceServer.bindTo(builder).build();

  private TurnstileHumanCheck check(Set<String> hostnames, boolean failOpen) {
    return new TurnstileHumanCheck(
        builder.build(),
        URI.create(TurnstileHumanCheck.DEFAULT_VERIFY_URL),
        SECRET,
        hostnames,
        failOpen);
  }

  private void cloudflareAnswers(String json) {
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void passes_when_cloudflare_says_success_for_the_action() {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("secret", SECRET);
    form.add("response", "tok");
    form.add("remoteip", "203.0.113.7");
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().formData(form))
        .andRespond(
            withSuccess(
                "{\"success\":true,\"action\":\"email-send\",\"hostname\":\"app.example.com\"}",
                MediaType.APPLICATION_JSON));

    assertThat(check(Set.of(), false).passes(SEND)).isTrue();
    cloudflare.verify();
  }

  @Test
  void fails_when_cloudflare_says_no() {
    cloudflareAnswers("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");

    assertThat(check(Set.of(), false).passes(SEND)).isFalse();
  }

  @Test
  void fails_on_a_wrong_action() {
    cloudflareAnswers(
        "{\"success\":true,\"action\":\"newsletter\",\"hostname\":\"app.example.com\"}");

    assertThat(check(Set.of(), false).passes(SEND)).isFalse();
  }

  @Test
  void fails_with_no_action_in_the_answer() {
    cloudflareAnswers("{\"success\":true,\"hostname\":\"app.example.com\"}");

    assertThat(check(Set.of(), false).passes(SEND)).isFalse();
  }

  @Test
  void fails_on_a_hostname_not_listed() {
    cloudflareAnswers("{\"success\":true,\"action\":\"email-send\",\"hostname\":\"evil.example\"}");

    assertThat(check(Set.of("app.example.com"), false).passes(SEND)).isFalse();
  }

  @Test
  void passes_a_listed_hostname() {
    cloudflareAnswers(
        "{\"success\":true,\"action\":\"email-send\",\"hostname\":\"app.example.com\"}");

    assertThat(check(Set.of("app.example.com"), false).passes(SEND)).isTrue();
  }

  @Test
  void a_missing_token_fails_without_calling_cloudflare() {
    assertThat(
            check(Set.of(), true)
                .passes(new HumanCheckRequest(Optional.empty(), "203.0.113.7", "email-send")))
        .isFalse();
    cloudflare.verify();
  }

  @Test
  void a_timeout_fails() {
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andRespond(withException(new SocketTimeoutException("slow")));

    assertThat(check(Set.of(), false).passes(SEND)).isFalse();
  }

  @Test
  void a_5xx_fails() {
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andRespond(withServerError());

    assertThat(check(Set.of(), false).passes(SEND)).isFalse();
  }

  @Test
  void an_answer_that_is_not_json_fails() {
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

    assertThat(check(Set.of(), false).passes(SEND)).isFalse();
  }

  @Test
  void fail_open_lets_an_outage_through() {
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andRespond(withServerError());

    assertThat(check(Set.of(), true).passes(SEND)).isTrue();
  }

  @Test
  void fail_open_lets_a_timeout_through() {
    cloudflare
        .expect(requestTo(TurnstileHumanCheck.DEFAULT_VERIFY_URL))
        .andRespond(withException(new SocketTimeoutException("slow")));

    assertThat(check(Set.of(), true).passes(SEND)).isTrue();
  }

  @Test
  void fail_open_still_honours_a_refusal() {
    cloudflareAnswers("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");

    assertThat(check(Set.of(), true).passes(SEND)).isFalse();
  }

  @Test
  void never_logs_the_secret_or_the_token() {
    Logger spring = (Logger) LoggerFactory.getLogger("org.springframework.web");
    Level before = spring.getLevel();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    spring.addAppender(logs);
    spring.setLevel(Level.TRACE);
    try {
      cloudflareAnswers("{\"success\":true,\"action\":\"email-send\"}");
      check(Set.of(), false).passes(SEND);
    } finally {
      spring.detachAppender(logs);
      spring.setLevel(before);
    }

    assertThat(logs.list)
        .isNotEmpty()
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(line -> line.contains(SECRET) || line.contains("tok"));
  }

  @Test
  void asks_the_verify_url_it_is_given() {
    cloudflare
        .expect(requestTo("http://localhost:9999/siteverify"))
        .andRespond(
            withSuccess(
                "{\"success\":true,\"action\":\"email-send\"}", MediaType.APPLICATION_JSON));

    assertThat(
            new TurnstileHumanCheck(
                    builder.build(),
                    URI.create("http://localhost:9999/siteverify"),
                    SECRET,
                    Set.of(),
                    false)
                .passes(SEND))
        .isTrue();
    cloudflare.verify();
  }

  @Test
  void is_named_turnstile() {
    assertThat(check(Set.of(), false).name()).isEqualTo("turnstile");
  }
}
