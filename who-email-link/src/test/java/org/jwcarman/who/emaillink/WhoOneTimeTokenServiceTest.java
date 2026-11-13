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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;

class WhoOneTimeTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(15);

  private final WhoOneTimeTokenService service =
      new WhoOneTimeTokenService(Clock.fixed(NOW, ZoneOffset.UTC), TTL);

  @Test
  void generateTouchesNoTable() {
    OneTimeToken token = service.generate(new GenerateOneTimeTokenRequest("owner@example.com"));

    assertThat(token.getTokenValue()).hasSize(43);
    assertThat(token.getUsername()).isEqualTo("owner@example.com");
    assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(TTL));
  }

  @Test
  void consumeIsUnsupported() {
    OneTimeTokenAuthenticationToken token = new OneTimeTokenAuthenticationToken("value");

    assertThatThrownBy(() -> service.consume(token))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("who's EmailLinkAuthenticationProvider redeems sign-ins");
  }
}
