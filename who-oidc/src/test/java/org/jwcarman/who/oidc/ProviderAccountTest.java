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
package org.jwcarman.who.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProviderAccountTest {

  private static final SignInRegistration GOOGLE =
      new SignInRegistration(
          "google", "https://accounts.google.com", SignInRegistration.Kind.OIDC, null, true, false);
  private static final SignInRegistration ACME =
      new SignInRegistration(
          "acme", "https://idp.example.com", SignInRegistration.Kind.OIDC, null, false, true);
  private static final SignInRegistration GITHUB =
      new SignInRegistration(
          "github", "https://github.com", SignInRegistration.Kind.GITHUB, "id", true, false);

  private static OAuth2User oidcUser(Map<String, Object> claims) {
    OidcIdToken.Builder token =
        OidcIdToken.withTokenValue("token")
            .subject("alice-sub")
            .issuer("https://accounts.google.com")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    claims.forEach(token::claim);
    return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), token.build());
  }

  private static OAuth2User gitHubUser(Map<String, Object> attributes) {
    return new DefaultOAuth2User(
        AuthorityUtils.createAuthorityList("OAUTH2_USER"), attributes, "id");
  }

  @Nested
  class Oidc {

    @Test
    void oidc_email_is_used_only_when_verified_and_trusted() {
      ProviderAccount verified =
          ProviderAccount.from(
              oidcUser(Map.of("email", "alice@example.com", "email_verified", true)), GOOGLE);
      ProviderAccount unverified =
          ProviderAccount.from(
              oidcUser(Map.of("email", "alice@example.com", "email_verified", false)), GOOGLE);
      ProviderAccount unsaid = ProviderAccount.from(oidcUser(Map.of("email", "a@b.com")), GOOGLE);

      assertThat(verified.subject()).isEqualTo("alice-sub");
      assertThat(verified.verifiedEmail()).contains("alice@example.com");
      assertThat(unverified.verifiedEmail()).isEmpty();
      assertThat(unsaid.verifiedEmail()).isEmpty();
    }

    @Test
    void oidc_untrusted_provider_gives_no_email() {
      ProviderAccount account =
          ProviderAccount.from(
              oidcUser(Map.of("email", "alice@example.com", "email_verified", true)), ACME);

      assertThat(account.verifiedEmail()).isEmpty();
      assertThat(account.displayName()).isEqualTo("alice@example.com");
    }

    @Test
    void display_name_falls_back_to_preferred_username_then_subject() {
      assertThat(
              ProviderAccount.from(oidcUser(Map.of("preferred_username", "alice")), ACME)
                  .displayName())
          .isEqualTo("alice");
      assertThat(ProviderAccount.from(oidcUser(Map.of()), ACME).displayName())
          .isEqualTo("alice-sub");
    }

    @Test
    void claims_keep_only_simple_values() {
      ProviderAccount account =
          ProviderAccount.from(
              oidcUser(
                  Map.of(
                      "hd",
                      "example.com",
                      "email_verified",
                      true,
                      "age",
                      42,
                      "groups",
                      List.of("a", "b"),
                      "address",
                      Map.of("country", "US"))),
              GOOGLE);

      assertThat(account.claims())
          .containsEntry("hd", "example.com")
          .containsEntry("email_verified", true)
          .containsEntry("age", 42)
          .containsEntry("sub", "alice-sub")
          .doesNotContainKeys("groups", "address", "iat", "exp");
    }
  }

  @Nested
  class GitHub {

    @Test
    void github_subject_is_the_numeric_id_as_text() {
      ProviderAccount account =
          ProviderAccount.from(gitHubUser(Map.of("id", 583231L, "login", "octocat")), GITHUB);

      assertThat(account.subject()).isEqualTo("583231");
      assertThat(account.displayName()).isEqualTo("octocat");
      assertThat(account.verifiedEmail()).isEmpty();
      assertThat(account.claims()).isEmpty();
    }

    @Test
    void github_verified_email_attribute_is_used_when_trusted() {
      Map<String, Object> attributes =
          Map.of("id", 1, WhoOAuth2UserService.VERIFIED_EMAIL, "octocat@github.com");

      assertThat(ProviderAccount.from(gitHubUser(attributes), GITHUB).verifiedEmail())
          .contains("octocat@github.com");
      SignInRegistration untrusted =
          new SignInRegistration(
              "github", "https://github.com", SignInRegistration.Kind.GITHUB, "id", false, false);
      assertThat(ProviderAccount.from(gitHubUser(attributes), untrusted).verifiedEmail()).isEmpty();
    }

    @Test
    void missing_subject_attribute_is_an_error() {
      SignInRegistration custom =
          new SignInRegistration(
              "acme", "https://acme.com", SignInRegistration.Kind.OAUTH2, "user_id", false, false);

      assertThatThrownBy(() -> ProviderAccount.from(gitHubUser(Map.of("id", 1)), custom))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("user_id");
    }
  }
}
