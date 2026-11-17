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
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link OidcLoginMethod} built by hand, as an application without who's autoconfiguration would:
 * no event publisher and no exception handoff beans.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcLoginMethodTest {

  private static final ClientRegistration ACME =
      ClientRegistration.withRegistrationId("acme")
          .clientId("client")
          .clientSecret("secret")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
          .scope("openid")
          .issuerUri("https://idp.example.com")
          .authorizationUri("https://idp.example.com/authorize")
          .tokenUri("https://idp.example.com/token")
          .jwkSetUri("https://idp.example.com/jwks")
          .userNameAttributeName("sub")
          .build();

  @Configuration
  @EnableWebSecurity
  static class Method {
    @Bean
    OidcLoginMethod oidcLoginMethod() {
      return method(SignInRegistrations.of(List.of(ACME), Map.of()));
    }
  }

  private static OidcLoginMethod method(SignInRegistrations registrations) {
    WhoService whoService = mock(WhoService.class);
    Clock clock = Clock.systemUTC();
    return new OidcLoginMethod(
        WhoPaths.defaults(),
        registrations,
        whoService,
        mock(FederatedCredentialRepository.class),
        mock(FederatedCredentialService.class),
        mock(LastCredentialGuard.class),
        new WhoSessionSecurityContextRepository(
            whoService,
            InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
            clock,
            WhoEventPublisher.NOOP),
        mock(OidcSignUpService.class),
        mock(IdentityRepository.class),
        new OidcRedirects("/", "/signin"),
        clock,
        Duration.ofMinutes(10),
        Duration.ofMinutes(10));
  }

  private static ClientRegistration provider(String id) {
    return ClientRegistration.withClientRegistration(ACME)
        .registrationId(id)
        .issuerUri("https://" + id + ".example.com")
        .build();
  }

  @Test
  void is_named_oidc_with_its_providers_sorted() {
    SignInRegistrations registrations =
        SignInRegistrations.of(List.of(provider("keycloak"), provider("github")), Map.of());

    assertThat(method(registrations).name()).isEqualTo("oidc (github, keycloak)");
  }

  @Test
  void sign_in_options_list_providers_by_registration_in_declared_order() {
    ClientRegistration okta =
        ClientRegistration.withClientRegistration(provider("okta")).clientName("Acme SSO").build();
    ClientRegistration github =
        ClientRegistration.withClientRegistration(provider("github")).clientName("GitHub").build();
    SignInRegistrations registrations = SignInRegistrations.of(List.of(okta, github), Map.of());

    assertThat(method(registrations).signInOptions())
        .isEqualTo(
            Map.of(
                "providers",
                List.of(
                    Map.of("id", "okta", "name", "Acme SSO"),
                    Map.of("id", "github", "name", "GitHub"))));
  }

  @Test
  void without_providers_the_sign_in_options_list_none() {
    assertThat(method(SignInRegistrations.of(List.of(), Map.of())).signInOptions())
        .isEqualTo(Map.of("providers", List.of()));
  }

  @Test
  void without_providers_is_named_oidc() {
    assertThat(method(SignInRegistrations.of(List.of(), Map.of())).name()).isEqualTo("oidc");
  }

  @Configuration
  static class Clients {
    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
      return new InMemoryClientRegistrationRepository(ACME);
    }
  }

  @Configuration
  static class Chain {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, OidcLoginMethod method) {
      method.configure(http);
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  /** Records the registrations the chain shares after who configures it. */
  @Configuration
  static class ChainRecordingShared {
    static final AtomicReference<ClientRegistrationRepository> SHARED = new AtomicReference<>();

    @Bean
    SecurityFilterChain chain(
        HttpSecurity http, OidcLoginMethod method, ClientRegistrationRepository clients) {
      // as an application's own oauth2Client() would find it
      http.setSharedObject(ClientRegistrationRepository.class, clients);
      method.configure(http);
      SHARED.set(http.getSharedObject(ClientRegistrationRepository.class));
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Configuration
  static class ChainWithoutSessionManagement {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, OidcLoginMethod method) {
      http.sessionManagement(s -> s.disable());
      method.configure(http);
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated());
      return http.build();
    }
  }

  @Test
  void without_its_own_beans_it_still_starts_sign_in_at_the_provider() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Method.class, Clients.class, Chain.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              String location =
                  MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                      .apply(springSecurity())
                      .build()
                      .perform(get("/who/oidc/authorize/acme"))
                      .andReturn()
                      .getResponse()
                      .getRedirectedUrl();
              assertThat(location).startsWith("https://idp.example.com/authorize");
            });
  }

  @Test
  void the_chain_keeps_sharing_the_application_s_registrations() {
    ChainRecordingShared.SHARED.set(null);
    new WebApplicationContextRunner()
        .withUserConfiguration(Method.class, Clients.class, ChainRecordingShared.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // an application's own oauth2Client() on the chain reads this one
              assertThat(ChainRecordingShared.SHARED.get())
                  .isSameAs(context.getBean(ClientRegistrationRepository.class));
            });
  }

  @Test
  void without_client_registrations_startup_fails_saying_so() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Method.class, Chain.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("needs client registrations"));
  }

  @Test
  void without_session_management_startup_fails_saying_so() {
    new WebApplicationContextRunner()
        .withUserConfiguration(Method.class, Clients.class, ChainWithoutSessionManagement.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("needs the chain's session management"));
  }
}
