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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasskeySettingsTest {

  private static final Duration WINDOW = Duration.ofMinutes(10);

  private static PasskeySettings withOrigin(String origin) {
    return new PasskeySettings("app.example.com", "App", Set.of(origin), WINDOW);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://app.example.com",
        "https://app.example.com:8443",
        "http://localhost:8080",
        "HTTPS://app.example.com"
      })
  void absoluteHttpOriginsAreAccepted(String origin) {
    assertThat(withOrigin(origin).allowedOrigins()).containsExactly(origin);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "app.example.com",
        "https://app.example.com/",
        "https://app.example.com/signin",
        "https://app.example.com?x=1",
        "https://app.example.com#top",
        "https://user@app.example.com",
        "ftp://app.example.com",
        "https://",
        "https:app.example.com",
        "https://app example.com",
        ""
      })
  void anythingButAnAbsoluteHttpOriginFails(String origin) {
    assertThatThrownBy(() -> withOrigin(origin))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("who.passkey.allowed-origins");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void blankRpNameFails(String rpName) {
    assertThatThrownBy(
            () ->
                new PasskeySettings(
                    "app.example.com", rpName, Set.of("https://app.example.com"), WINDOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("who.passkey.rp-name");
  }
}
