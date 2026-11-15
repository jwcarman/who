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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.test.StubOidcProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoOAuth2UserServiceTest {

  private StubOidcProvider gitHub;
  private ClientRegistration registration;
  private WhoOAuth2UserService service;

  @BeforeEach
  void setUp() {
    gitHub = StubOidcProvider.startGitHub();
    gitHub.nextUser("583231", Map.of("login", "octocat"));
    gitHub.nextEmails(
        List.of(
            Map.of("email", "old@example.com", "primary", false, "verified", true),
            Map.of("email", "octocat@github.com", "primary", true, "verified", true)));
    registration =
        ClientRegistration.withRegistrationId("github")
            .clientId("client")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("read:user", "user:email")
            .authorizationUri(gitHub.issuer() + "/login/oauth/authorize")
            .tokenUri(gitHub.issuer() + "/login/oauth/access_token")
            .userInfoUri(gitHub.issuer() + "/user")
            .userNameAttributeName("id")
            .build();
    SignInRegistration signIn =
        new SignInRegistration(
            "github", "https://github.com", SignInRegistration.Kind.GITHUB, "id", true);
    service =
        new WhoOAuth2UserService(
            id -> "github".equals(id) ? Optional.of(signIn) : Optional.empty());
  }

  @AfterEach
  void tearDown() {
    gitHub.close();
  }

  private OAuth2User load(Set<String> scopes) {
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            scopes);
    return service.loadUser(new OAuth2UserRequest(registration, token));
  }

  @Test
  void github_verified_primary_email_is_added() {
    OAuth2User user = load(Set.of("read:user", "user:email"));

    assertThat(user.<String>getAttribute(WhoOAuth2UserService.VERIFIED_EMAIL))
        .isEqualTo("octocat@github.com");
    assertThat(user.<String>getAttribute("login")).isEqualTo("octocat");
  }

  @Test
  void github_comma_separated_granted_scope_counts() {
    OAuth2User user = load(Set.of("read:user,user:email"));

    assertThat(user.<String>getAttribute(WhoOAuth2UserService.VERIFIED_EMAIL))
        .isEqualTo("octocat@github.com");
  }

  @Test
  void github_unverified_primary_email_is_not_added() {
    gitHub.nextEmails(
        List.of(Map.of("email", "octocat@github.com", "primary", true, "verified", false)));

    assertThat(load(Set.of("user:email")).getAttributes())
        .doesNotContainKey(WhoOAuth2UserService.VERIFIED_EMAIL);
  }

  @Test
  void github_without_user_email_scope_makes_no_call() {
    OAuth2User user = load(Set.of("read:user"));

    assertThat(user.getAttributes()).doesNotContainKey(WhoOAuth2UserService.VERIFIED_EMAIL);
    assertThat(gitHub.emailRequests()).isZero();
  }

  @Test
  void github_emails_failure_still_signs_in() {
    gitHub.failEmails(500);

    OAuth2User user = load(Set.of("user:email"));

    assertThat(user.getAttributes()).doesNotContainKey(WhoOAuth2UserService.VERIFIED_EMAIL);
    assertThat(user.<String>getAttribute("login")).isEqualTo("octocat");
  }

  @Test
  void github_emails_timeout_still_signs_in() {
    gitHub.delayEmails(Duration.ofSeconds(5));
    long start = System.nanoTime();

    OAuth2User user = load(Set.of("user:email"));

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    assertThat(user.getAttributes()).doesNotContainKey(WhoOAuth2UserService.VERIFIED_EMAIL);
  }

  @Test
  void a_registration_who_does_not_sign_in_with_is_left_alone() {
    WhoOAuth2UserService none = new WhoOAuth2UserService(id -> Optional.empty());
    OAuth2AccessToken token =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Set.of("user:email"));

    OAuth2User user = none.loadUser(new OAuth2UserRequest(registration, token));

    assertThat(user.getAttributes()).doesNotContainKey(WhoOAuth2UserService.VERIFIED_EMAIL);
    assertThat(gitHub.emailRequests()).isZero();
  }
}
