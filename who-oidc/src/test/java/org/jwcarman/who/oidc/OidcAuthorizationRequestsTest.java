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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcAuthorizationRequestsTest {

  private static final UUID IDENTITY = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

  private static final List<ClientRegistration> CLIENTS =
      List.of(
          oidc("acme", "https://idp.example.com"),
          oidc("google", "https://accounts.google.com"),
          oauth2("github", "github.com", "api.github.com"),
          oauth2("plain", "auth.plain.example.com", "api.plain.example.com"),
          ClientRegistration.withRegistrationId("machine")
              .clientId("client")
              .clientSecret("secret")
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .tokenUri("https://idp.example.com/token")
              .build());

  private final OidcAuthorizationRequests requests =
      new OidcAuthorizationRequests(
          new InMemoryClientRegistrationRepository(CLIENTS),
          SignInRegistrations.of(
              CLIENTS,
              Map.of(
                  "plain",
                  new ProviderSettings(true, "https://plain.example.com", "user_id", null))),
          "/who/oidc/authorize");

  private static ClientRegistration oidc(String id, String issuer) {
    return ClientRegistration.withRegistrationId(id)
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid", "email")
        .issuerUri(issuer)
        .authorizationUri(issuer + "/authorize")
        .tokenUri(issuer + "/token")
        .jwkSetUri(issuer + "/jwks")
        .userNameAttributeName("sub")
        .build();
  }

  private static ClientRegistration oauth2(String id, String authHost, String apiHost) {
    return ClientRegistration.withRegistrationId(id)
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        // Boot's default, whose {action} a link must fill with "login"
        .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
        .scope("read:user")
        .authorizationUri("https://" + authHost + "/login/oauth/authorize")
        .tokenUri("https://" + authHost + "/login/oauth/access_token")
        .userInfoUri("https://" + apiHost + "/user")
        .userNameAttributeName("id")
        .build();
  }

  private OAuth2AuthorizationRequest link(String registrationId) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/who/oidc/link/" + registrationId);
    return requests.link(request, registrationId, IDENTITY, NOW).orElseThrow();
  }

  @Nested
  class A_link {

    @Test
    void carries_its_intent_in_the_attributes() {
      OAuth2AuthorizationRequest acme = link("acme");

      assertThat(LinkIntent.readFrom(acme.getAttributes())).contains(new LinkIntent(IDENTITY, NOW));
    }

    @Test
    void comes_back_to_the_sign_in_callback() {
      assertThat(link("plain").getRedirectUri()).endsWith("/login/oauth2/code/plain");
    }

    @Test
    void through_openid_connect_asks_the_person_to_sign_in_again() {
      assertThat(link("acme").getAdditionalParameters())
          .containsEntry("prompt", "login")
          .doesNotContainKey("max_age");
    }

    @Test
    void through_google_asks_which_account() {
      assertThat(link("google").getAdditionalParameters())
          .containsEntry("prompt", "select_account")
          .doesNotContainKey("max_age");
    }

    @Test
    void through_github_asks_which_account() {
      assertThat(link("github").getAdditionalParameters())
          .containsEntry("prompt", "select_account")
          .doesNotContainKey("max_age");
    }

    @Test
    void through_another_oauth2_provider_asks_for_nothing_extra() {
      assertThat(link("plain").getAdditionalParameters()).doesNotContainKeys("prompt", "max_age");
    }

    @Test
    void through_a_registration_who_does_not_sign_in_with_is_empty() {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", "/who/oidc/link/machine");

      assertThat(requests.link(request, "machine", IDENTITY, NOW)).isEmpty();
      assertThat(requests.link(request, "nope", IDENTITY, NOW)).isEmpty();
    }
  }

  @Nested
  class A_plain_sign_in {

    @Test
    void carries_no_intent_and_asks_for_nothing_extra() {
      OAuth2AuthorizationRequest acme =
          requests.resolve(new MockHttpServletRequest("GET", "/who/oidc/authorize/acme"));

      assertThat(LinkIntent.readFrom(acme.getAttributes())).isEmpty();
      assertThat(acme.getAdditionalParameters()).doesNotContainKeys("prompt", "max_age");
    }

    @Test
    void resolved_by_registration_id_keeps_spring_s_meaning_and_only_for_who_s_registrations() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");

      OAuth2AuthorizationRequest github = requests.resolve(request, "github");

      // Spring asks this form for a client's authorization, so {action} is "authorize"
      assertThat(github.getRedirectUri()).endsWith("/authorize/oauth2/code/github");
      assertThat(LinkIntent.readFrom(github.getAttributes())).isEmpty();
      assertThat(requests.resolve(request, "machine")).isNull();
    }

    @Test
    void through_a_registration_who_does_not_sign_in_with_is_not_resolved() {
      assertThat(requests.resolve(new MockHttpServletRequest("GET", "/who/oidc/authorize/machine")))
          .isNull();
      assertThat(requests.resolve(new MockHttpServletRequest("GET", "/elsewhere"))).isNull();
    }
  }
}
