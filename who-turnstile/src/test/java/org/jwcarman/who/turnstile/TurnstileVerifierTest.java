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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.spring.security.HumanCheckRequest;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TurnstileVerifierTest {

  private static final HumanCheckRequest SEND =
      new HumanCheckRequest(Optional.of("tok"), "203.0.113.7", "email-send");

  private static TurnstileAnswer answer(boolean success, String action, String hostname) {
    return new TurnstileAnswer(
        success, Optional.ofNullable(action), Optional.ofNullable(hostname), List.of());
  }

  @Test
  void strict_passes_success_for_the_action() {
    assertThat(
            TurnstileVerifier.strict(Set.of()).accepts(answer(true, "email-send", "a.com"), SEND))
        .isTrue();
  }

  @Test
  void strict_fails_without_success() {
    assertThat(TurnstileVerifier.strict(Set.of()).accepts(answer(false, "email-send", null), SEND))
        .isFalse();
  }

  @Test
  void strict_fails_another_or_no_action() {
    TurnstileVerifier strict = TurnstileVerifier.strict(Set.of());
    assertThat(strict.accepts(answer(true, "email-sign-in", null), SEND)).isFalse();
    assertThat(strict.accepts(answer(true, null, null), SEND)).isFalse();
  }

  @Test
  void strict_checks_listed_hostnames_ignoring_case() {
    TurnstileVerifier strict = TurnstileVerifier.strict(Set.of(" App.Example.com "));
    assertThat(strict.accepts(answer(true, "email-send", "APP.example.com"), SEND)).isTrue();
    assertThat(strict.accepts(answer(true, "email-send", "evil.example"), SEND)).isFalse();
    assertThat(strict.accepts(answer(true, "email-send", null), SEND)).isFalse();
  }

  @Test
  void success_only_ignores_action_and_hostname() {
    TurnstileVerifier successOnly = TurnstileVerifier.successOnly();
    assertThat(successOnly.accepts(answer(true, null, "example.com"), SEND)).isTrue();
    assertThat(successOnly.accepts(answer(false, "email-send", null), SEND)).isFalse();
  }

  @Test
  void an_answer_is_read_from_cloudflares_json() {
    assertThat(
            TurnstileAnswer.of(
                Map.of(
                    "success",
                    true,
                    "action",
                    "email-send",
                    "hostname",
                    "app.example.com",
                    "error-codes",
                    List.of("x", 3))))
        .isEqualTo(
            new TurnstileAnswer(
                true, Optional.of("email-send"), Optional.of("app.example.com"), List.of("x")));
  }

  @Test
  void a_success_that_is_not_true_is_no_success() {
    assertThat(TurnstileAnswer.of(Map.of("success", "true")).success()).isFalse();
    assertThat(TurnstileAnswer.of(Map.of()).success()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1x0000000000000000000000000000000AA",
        "2x0000000000000000000000000000000AA",
        "3x0000000000000000000000000000000AA"
      })
  void knows_cloudflares_test_secrets(String secret) {
    assertThat(TurnstileVerifier.isTestSecret(secret)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0x4AAAAAAAsecret",
        "1x0000000000000000000000000000000AB",
        "1x0000000000000000000000000000000AA "
      })
  void any_other_secret_is_real(String secret) {
    assertThat(TurnstileVerifier.isTestSecret(secret)).isFalse();
  }
}
