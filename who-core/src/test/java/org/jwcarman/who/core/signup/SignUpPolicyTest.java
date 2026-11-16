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
package org.jwcarman.who.core.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.SignInMethod;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SignUpPolicyTest {

  private static SignUpRequest request(Map<String, Object> claims) {
    return new SignUpRequest(
        SignInMethod.OIDC,
        "google",
        "https://accounts.google.com",
        "1234567890",
        Optional.of("jane@example.com"),
        claims);
  }

  @Nested
  class Closed_policy {

    @Test
    void refuses_every_request() {
      SignUpDecision decision =
          new ClosedSignUpPolicy().decide(request(Map.of("hd", "example.com")));

      assertThat(decision).isInstanceOf(SignUpDecision.Refuse.class);
    }
  }

  @Nested
  class Request {

    @Test
    void claims_are_immutable() {
      SignUpRequest request = request(new HashMap<>(Map.of("hd", "example.com")));

      Map<String, Object> claims = request.claims();

      assertThatThrownBy(() -> claims.put("x", 1))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void claims_are_copied() {
      Map<String, Object> claims = new HashMap<>(Map.of("hd", "example.com"));
      SignUpRequest request = request(claims);

      claims.put("hd", "evil.example");

      assertThat(request.claims()).containsEntry("hd", "example.com");
    }

    @Test
    void rejects_a_null_issuer() {
      assertThatThrownBy(
              () ->
                  new SignUpRequest(
                      SignInMethod.OIDC, "google", null, "sub", Optional.empty(), Map.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("issuer");
    }

    @Test
    void rejects_a_null_verified_email_optional() {
      assertThatThrownBy(
              () ->
                  new SignUpRequest(
                      SignInMethod.OIDC,
                      "google",
                      "https://accounts.google.com",
                      "sub",
                      null,
                      Map.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("verifiedEmail");
    }
  }

  @Nested
  class Decision {

    @Test
    void allow_without_setup_has_a_no_op_setup() {
      SignUpDecision.Allow allow = SignUpDecision.allow();

      allow.setup().accept(Identity.create());

      assertThat(allow.setup()).isNotNull();
    }

    @Test
    void allow_keeps_its_setup() {
      Identity[] seen = new Identity[1];
      SignUpDecision.Allow allow = SignUpDecision.allow(identity -> seen[0] = identity);
      Identity identity = Identity.create();

      allow.setup().accept(identity);

      assertThat(seen[0]).isSameAs(identity);
    }

    @Test
    void allow_rejects_a_null_setup() {
      assertThatThrownBy(() -> SignUpDecision.allow(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void refuse_is_a_refusal() {
      assertThat(SignUpDecision.refuse()).isInstanceOf(SignUpDecision.Refuse.class);
    }
  }
}
