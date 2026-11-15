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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SignInRegistrationsTest {

  private static ClientRegistration.Builder oidc(String id) {
    return ClientRegistration.withRegistrationId(id)
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid", "email")
        .authorizationUri("https://idp.example.com/authorize")
        .tokenUri("https://idp.example.com/token")
        .jwkSetUri("https://idp.example.com/jwks")
        .userInfoUri("https://idp.example.com/userinfo")
        .userNameAttributeName("sub");
  }

  private static ClientRegistration.Builder oauth2(String id, String authHost, String apiHost) {
    return ClientRegistration.withRegistrationId(id)
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("read:user", "user:email")
        .authorizationUri("https://" + authHost + "/login/oauth/authorize")
        .tokenUri("https://" + authHost + "/login/oauth/access_token")
        .userInfoUri("https://" + apiHost + "/user")
        .userNameAttributeName("id");
  }

  private static SignInRegistrations of(
      Map<String, ProviderSettings> settings, ClientRegistration... registrations) {
    return SignInRegistrations.of(List.of(registrations), settings);
  }

  private static ProviderSettings settings(String issuer, String subjectAttribute) {
    return new ProviderSettings(true, issuer, subjectAttribute, null, null);
  }

  @Nested
  class Oidc {

    @Test
    void google_issuer_uri_is_the_issuer_and_email_is_trusted() {
      SignInRegistration google =
          of(Map.of(), oidc("google").issuerUri("https://accounts.google.com").build())
              .find("google")
              .orElseThrow();

      assertThat(google.issuer()).isEqualTo("https://accounts.google.com");
      assertThat(google.oidc()).isTrue();
      assertThat(google.kind()).isEqualTo(SignInRegistration.Kind.OIDC);
      assertThat(google.subjectAttribute()).isNull();
      assertThat(google.trustEmail()).isTrue();
    }

    @Test
    void endpoint_oidc_registration_without_issuer_is_invalid() {
      assertThatThrownBy(() -> of(Map.of(), oidc("acme").build()))
          .isInstanceOfSatisfying(
              InvalidRegistrationException.class,
              e -> {
                assertThat(e.registrationId()).isEqualTo("acme");
                assertThat(e.property()).isEqualTo("who.oidc.providers.acme.issuer");
              });
    }

    @Test
    void endpoint_oidc_registration_takes_the_configured_issuer() {
      SignInRegistration acme =
          of(Map.of("acme", settings("https://idp.example.com", null)), oidc("acme").build())
              .find("acme")
              .orElseThrow();

      assertThat(acme.issuer()).isEqualTo("https://idp.example.com");
      assertThat(acme.oidc()).isTrue();
    }

    @Test
    void configured_issuer_that_disagrees_with_the_issuer_uri_is_invalid() {
      ClientRegistration google = oidc("google").issuerUri("https://accounts.google.com").build();
      Map<String, ProviderSettings> settings =
          Map.of("google", settings("https://evil.example.com", null));

      assertThatThrownBy(() -> of(settings, google))
          .isInstanceOf(InvalidRegistrationException.class)
          .hasMessageContaining("who.oidc.providers.google.issuer");
    }

    @Test
    void unknown_provider_does_not_trust_email() {
      SignInRegistration acme =
          of(Map.of(), oidc("acme").issuerUri("https://idp.example.com").build())
              .find("acme")
              .orElseThrow();

      assertThat(acme.trustEmail()).isFalse();
    }

    @Test
    void configured_trust_email_wins() {
      SignInRegistration acme =
          of(
                  Map.of("acme", new ProviderSettings(true, null, null, true, null)),
                  oidc("acme").issuerUri("https://idp.example.com").build())
              .find("acme")
              .orElseThrow();

      assertThat(acme.trustEmail()).isTrue();
    }

    @Test
    void a_link_asks_for_a_fresh_login_by_default() {
      SignInRegistration acme =
          of(Map.of(), oidc("acme").issuerUri("https://idp.example.com").build())
              .find("acme")
              .orElseThrow();

      assertThat(acme.freshLogin()).isTrue();
    }

    @Test
    void google_reports_no_auth_time_so_links_through_it_do_not_ask_for_a_fresh_login() {
      SignInRegistration google =
          of(Map.of(), oidc("google").issuerUri("https://accounts.google.com").build())
              .find("google")
              .orElseThrow();

      assertThat(google.freshLogin()).isFalse();
    }

    @Test
    void configured_fresh_login_wins() {
      SignInRegistration acme =
          of(
                  Map.of("acme", new ProviderSettings(true, null, null, null, false)),
                  oidc("acme").issuerUri("https://idp.example.com").build())
              .find("acme")
              .orElseThrow();

      assertThat(acme.freshLogin()).isFalse();
    }

    @Test
    void entra_common_is_invalid() {
      for (String tenant : List.of("common", "organizations", "consumers")) {
        ClientRegistration entra =
            oidc("entra")
                .issuerUri("https://login.microsoftonline.com/" + tenant + "/v2.0")
                .build();
        assertThatThrownBy(() -> of(Map.of(), entra))
            .isInstanceOf(InvalidRegistrationException.class)
            .hasMessageContaining(tenant);
      }
    }
  }

  @Nested
  class GitHub {

    @Test
    void github_default_issuer_and_subject() {
      SignInRegistration github =
          of(Map.of(), oauth2("github", "github.com", "api.github.com").build())
              .find("github")
              .orElseThrow();

      assertThat(github.issuer()).isEqualTo("https://github.com");
      assertThat(github.kind()).isEqualTo(SignInRegistration.Kind.GITHUB);
      assertThat(github.oidc()).isFalse();
      assertThat(github.subjectAttribute()).isEqualTo("id");
      assertThat(github.trustEmail()).isTrue();
      assertThat(github.freshLogin()).isFalse();
    }

    @Test
    void github_is_recognised_by_host_not_by_id() {
      ClientRegistration enterprise =
          oauth2("github", "github.acme.com", "github.acme.com").build();

      assertThatThrownBy(() -> of(Map.of(), enterprise))
          .isInstanceOf(InvalidRegistrationException.class);
    }

    @Test
    void github_under_another_id_is_still_github() {
      SignInRegistration gh =
          of(Map.of(), oauth2("gh", "github.com", "api.github.com").build())
              .find("gh")
              .orElseThrow();

      assertThat(gh.kind()).isEqualTo(SignInRegistration.Kind.GITHUB);
    }
  }

  @Nested
  class Plain_oauth2 {

    @Test
    void non_oidc_without_settings_is_invalid() {
      ClientRegistration acme = oauth2("acme", "auth.acme.com", "api.acme.com").build();

      assertThatThrownBy(() -> of(Map.of(), acme))
          .isInstanceOfSatisfying(
              InvalidRegistrationException.class,
              e -> assertThat(e.property()).isEqualTo("who.oidc.providers.acme.issuer"));
      assertThatThrownBy(() -> of(Map.of("acme", settings("https://acme.com", null)), acme))
          .isInstanceOfSatisfying(
              InvalidRegistrationException.class,
              e -> assertThat(e.property()).isEqualTo("who.oidc.providers.acme.subject-attribute"));
    }

    @Test
    void non_oidc_with_settings_is_valid() {
      SignInRegistration acme =
          of(
                  Map.of("acme", settings("https://acme.com", "user_id")),
                  oauth2("acme", "auth.acme.com", "api.acme.com").build())
              .find("acme")
              .orElseThrow();

      assertThat(acme.kind()).isEqualTo(SignInRegistration.Kind.OAUTH2);
      assertThat(acme.issuer()).isEqualTo("https://acme.com");
      assertThat(acme.subjectAttribute()).isEqualTo("user_id");
      assertThat(acme.trustEmail()).isFalse();
      assertThat(acme.freshLogin()).isFalse();
    }

    @Test
    void fresh_login_without_openid_connect_is_invalid() {
      ClientRegistration acme = oauth2("acme", "auth.acme.com", "api.acme.com").build();
      Map<String, ProviderSettings> settings =
          Map.of("acme", new ProviderSettings(true, "https://acme.com", "user_id", null, true));

      assertThatThrownBy(() -> of(settings, acme))
          .isInstanceOfSatisfying(
              InvalidRegistrationException.class,
              e -> assertThat(e.property()).isEqualTo("who.oidc.providers.acme.fresh-login"));
    }
  }

  @Nested
  class Selection {

    @Test
    void duplicate_issuer_is_invalid() {
      ClientRegistration one = oidc("one").issuerUri("https://idp.example.com").build();
      ClientRegistration two = oidc("two").issuerUri("https://idp.example.com").build();

      assertThatThrownBy(() -> of(Map.of(), one, two))
          .isInstanceOf(InvalidRegistrationException.class)
          .hasMessageContaining("one");
    }

    @Test
    void client_credentials_registrations_are_ignored() {
      ClientRegistration machine =
          ClientRegistration.withRegistrationId("machine")
              .clientId("client")
              .clientSecret("secret")
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .tokenUri("https://idp.example.com/token")
              .build();

      SignInRegistrations registrations = of(Map.of(), machine);

      assertThat(registrations.isEmpty()).isTrue();
      assertThat(registrations.find("machine")).isEmpty();
    }

    @Test
    void disabled_registration_is_ignored() {
      SignInRegistrations registrations =
          of(
              Map.of("acme", new ProviderSettings(false, null, null, null, null)),
              oidc("acme").build(),
              oidc("google").issuerUri("https://accounts.google.com").build());

      assertThat(registrations.find("acme")).isEmpty();
      assertThat(registrations.all())
          .extracting(SignInRegistration::registrationId)
          .containsExactly("google");
    }
  }
}
