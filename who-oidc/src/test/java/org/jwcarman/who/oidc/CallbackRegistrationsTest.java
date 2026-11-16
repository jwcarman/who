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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CallbackRegistrationsTest {

  private static final WhoPaths PATHS = new WhoPaths("/auth");

  private static final List<ClientRegistration> ALL =
      List.of(
          registration("springs", CallbackRegistrations.SPRING_DEFAULT),
          registration("explicit", "https://app.example.com/auth/oidc/callback/explicit"),
          registration("off", CallbackRegistrations.SPRING_DEFAULT));

  private final CallbackRegistrations registrations =
      new CallbackRegistrations(
          new InMemoryClientRegistrationRepository(ALL),
          SignInRegistrations.of(
              ALL,
              Map.of(
                  "springs", settings(true, "springs"),
                  "explicit", settings(true, "explicit"),
                  "off", settings(false, "off"))),
          PATHS);

  private static ProviderSettings settings(boolean enabled, String id) {
    return new ProviderSettings(enabled, "https://" + id + ".example.com", "id", null);
  }

  private static ClientRegistration registration(String id, String redirectUri) {
    return ClientRegistration.withRegistrationId(id)
        .clientId("client")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(redirectUri)
        .scope("read:user")
        .authorizationUri("https://idp.example.com/authorize")
        .tokenUri("https://idp.example.com/token")
        .userInfoUri("https://idp.example.com/user")
        .userNameAttributeName("id")
        .build();
  }

  @Test
  void a_registration_at_spring_s_default_comes_back_to_who_s_callback() {
    assertThat(registrations.findByRegistrationId("springs").getRedirectUri())
        .isEqualTo("{baseUrl}/auth/oidc/callback/{registrationId}");
  }

  @Test
  void an_explicit_redirect_uri_is_used_as_written() {
    assertThat(registrations.findByRegistrationId("explicit").getRedirectUri())
        .isEqualTo("https://app.example.com/auth/oidc/callback/explicit");
  }

  @Test
  void a_registration_who_does_not_sign_in_with_is_used_as_written() {
    assertThat(registrations.findByRegistrationId("off").getRedirectUri())
        .isEqualTo(CallbackRegistrations.SPRING_DEFAULT);
  }

  @Test
  void an_unknown_registration_is_null() {
    assertThat(registrations.findByRegistrationId("unknown")).isNull();
  }

  @Test
  void listing_gives_each_registration_as_found() {
    assertThat(registrations)
        .extracting(ClientRegistration::getRedirectUri)
        .containsExactlyInAnyOrder(
            "{baseUrl}/auth/oidc/callback/{registrationId}",
            "https://app.example.com/auth/oidc/callback/explicit",
            CallbackRegistrations.SPRING_DEFAULT);
  }

  @Test
  void who_serves_spring_s_default_and_its_own_callback() {
    assertThat(problem("a", CallbackRegistrations.SPRING_DEFAULT)).isEmpty();
    assertThat(problem("a", "{baseUrl}/auth/oidc/callback/{registrationId}")).isEmpty();
    assertThat(problem("a", "https://app.example.com/auth/oidc/callback/a")).isEmpty();
  }

  @Test
  void who_does_not_serve_another_path_another_root_or_another_registration_s_callback() {
    assertThat(problem("a", "{baseUrl}/login/oauth2/code/{registrationId}"))
        .contains("is {baseUrl}/login/oauth2/code/{registrationId}, which who does not serve");
    assertThat(problem("a", "{baseUrl}/who/oidc/callback/{registrationId}")).isPresent();
    assertThat(problem("a", "https://app.example.com/auth/oidc/callback/b")).isPresent();
  }

  private static Optional<String> problem(String id, String redirectUri) {
    return CallbackRegistrations.problem(registration(id, redirectUri), PATHS);
  }
}
