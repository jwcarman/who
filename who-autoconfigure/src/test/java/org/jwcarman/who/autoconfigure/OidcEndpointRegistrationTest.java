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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Provider sign-in with a registration configured by its endpoints, not {@code issuer-uri}: Spring
 * then checks no issuer, so the only check is who's, from {@code who.oidc.providers.test.issuer}.
 */
@TestPropertySource(
    properties = {
      "spring.security.oauth2.client.registration.test.provider=endpoints",
      "spring.security.oauth2.client.registration.test.authorization-grant-type=authorization_code",
      "spring.security.oauth2.client.registration.test.redirect-uri={baseUrl}/who/oidc/callback/{registrationId}",
      "spring.security.oauth2.client.provider.endpoints.user-name-attribute=sub"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcEndpointRegistrationTest extends AbstractOidcTest {

  @DynamicPropertySource
  static void endpoints(DynamicPropertyRegistry registry) {
    String provider = "spring.security.oauth2.client.provider.endpoints.";
    registry.add(provider + "authorization-uri", () -> PROVIDER.issuer() + "/authorize");
    registry.add(provider + "token-uri", () -> PROVIDER.issuer() + "/token");
    registry.add(provider + "jwk-set-uri", () -> PROVIDER.issuer() + "/jwks");
    registry.add(provider + "user-info-uri", () -> PROVIDER.issuer() + "/userinfo");
    registry.add("who.oidc.providers.test.issuer", PROVIDER::issuer);
  }

  @Test
  void the_configured_issuer_signs_in() throws Exception {
    MvcResult result = signInAsAlice();

    assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS_URL);
    assertThat(me()).isEqualTo(200);
  }

  @Test
  void a_forged_issuer_is_rejected() throws Exception {
    PROVIDER.nextIssuerClaim("https://accounts.google.com");

    MvcResult result = signInAsAlice();

    assertThat(result.getResponse().getRedirectedUrl())
        .isEqualTo(FAILURE_URL + "?error=signin-failed");
    assertThat(events.stream(SignInFailed.class))
        .singleElement()
        .satisfies(e -> assertThat(e.reason()).isEqualTo(SignInFailureReason.PROVIDER_ERROR));
    assertThat(me()).isEqualTo(401);
  }
}
