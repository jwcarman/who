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
package org.jwcarman.who.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.security.core.authority.FactorGrantedAuthority;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoSignInFactorsTest {

  private static final Instant AT = Instant.parse("2026-11-16T10:00:00Z");

  @Test
  void email_and_codes_are_ott() {
    assertFactor(SignInMethod.EMAIL_LINK, FactorGrantedAuthority.OTT_AUTHORITY);
    assertFactor(SignInMethod.EMAIL_CODE, FactorGrantedAuthority.OTT_AUTHORITY);
  }

  @Test
  void passkeys_are_webauthn() {
    assertFactor(SignInMethod.PASSKEY, FactorGrantedAuthority.WEBAUTHN_AUTHORITY);
  }

  @Test
  void provider_sign_in_is_authorization_code() {
    assertFactor(SignInMethod.OIDC, FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
  }

  @Test
  void jwt_is_bearer() {
    assertFactor(SignInMethod.JWT, FactorGrantedAuthority.BEARER_AUTHORITY);
  }

  @Test
  void an_api_key_has_no_factor() {
    assertThat(WhoSignInFactors.of(SignInMethod.API_KEY, AT)).isEmpty();
  }

  @Test
  void the_authorities_are_the_four_factors() {
    assertThat(WhoSignInFactors.AUTHORITIES)
        .containsExactlyInAnyOrder(
            FactorGrantedAuthority.OTT_AUTHORITY,
            FactorGrantedAuthority.WEBAUTHN_AUTHORITY,
            FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY,
            FactorGrantedAuthority.BEARER_AUTHORITY);
  }

  @Test
  void is_recent_at_the_boundary() {
    Duration window = Duration.ofMinutes(10);
    assertThat(WhoSignInFactors.isRecent(AT, window, AT)).isTrue();
    assertThat(WhoSignInFactors.isRecent(AT, window, AT.plus(window).minusMillis(1))).isTrue();
    assertThat(WhoSignInFactors.isRecent(AT, window, AT.plus(window))).isFalse();
  }

  private static void assertFactor(SignInMethod method, String authority) {
    assertThat(WhoSignInFactors.of(method, AT))
        .hasValueSatisfying(
            factor -> {
              assertThat(factor.getAuthority()).isEqualTo(authority);
              assertThat(factor.getIssuedAt()).isEqualTo(AT);
            });
  }
}
