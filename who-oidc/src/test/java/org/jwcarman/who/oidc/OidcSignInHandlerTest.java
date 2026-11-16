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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;

/**
 * The handler's failure paths that a running app reaches only through a misconfiguration or a
 * repository other than who's: the end-to-end tests in who-autoconfigure cover the rest.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcSignInHandlerTest {

  private static final String ISSUER = "https://idp.example.com";
  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(10);

  private final WhoService whoService = mock(WhoService.class);
  private final FederatedCredentialRepository credentials =
      mock(FederatedCredentialRepository.class);
  private final WhoSessionSecurityContextRepository sessions =
      mock(WhoSessionSecurityContextRepository.class);
  private final List<WhoEvent> events = new ArrayList<>();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private OidcSignInHandler handler;

  private static ClientRegistration oidc() {
    return ClientRegistration.withRegistrationId("idp")
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid")
        .authorizationUri(ISSUER + "/authorize")
        .tokenUri(ISSUER + "/token")
        .jwkSetUri(ISSUER + "/jwks")
        .issuerUri(ISSUER)
        .userNameAttributeName("sub")
        .build();
  }

  /** A plain OAuth 2 provider whose who setting names an attribute Spring does not require. */
  private static ClientRegistration plain() {
    return ClientRegistration.withRegistrationId("plain")
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("read:user")
        .authorizationUri("https://plain.example.com/authorize")
        .tokenUri("https://plain.example.com/token")
        .userInfoUri("https://plain.example.com/user")
        .userNameAttributeName("id")
        .build();
  }

  @BeforeEach
  void setUp() {
    SignInRegistrations registrations =
        SignInRegistrations.of(
            List.of(oidc(), plain()),
            Map.of("plain", new ProviderSettings(true, "https://plain.example.com", "uid", null)));
    handler =
        new OidcSignInHandler(
            registrations,
            whoService,
            credentials,
            sessions,
            new NullAuthenticatedSessionStrategy(),
            new PendingSignUps(),
            new OidcRedirects("/home", "/signin", "/settings"),
            events::add,
            null,
            Clock.fixed(NOW, ZoneOffset.UTC),
            WINDOW,
            WINDOW);
  }

  private static OAuth2User oidcUser(String subject) {
    OidcIdToken idToken =
        OidcIdToken.withTokenValue("token")
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(NOW)
            .expiresAt(NOW.plusSeconds(60))
            .build();
    return new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken);
  }

  private SignInFailed onlyFailure() {
    assertThat(events).singleElement().isInstanceOf(SignInFailed.class);
    return (SignInFailed) events.getFirst();
  }

  /** A credential for {@code subject} whose link names an identity that no longer exists. */
  private UUID danglingCredential(String subject, UUID identityId) {
    FederatedCredential credential = FederatedCredential.create(ISSUER, subject);
    when(credentials.findByIssuerAndSubject(ISSUER, subject)).thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(any())).thenReturn(new Resolution.NoIdentity(identityId));
    return credential.id();
  }

  @Test
  void a_registration_who_does_not_sign_in_with_fails_as_a_provider_error() throws Exception {
    OAuth2AuthenticationToken token =
        new OAuth2AuthenticationToken(oidcUser("alice"), AuthorityUtils.NO_AUTHORITIES, "unknown");

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/signin?error=signin-failed");
    assertThat(onlyFailure().reason()).isEqualTo(SignInFailureReason.PROVIDER_ERROR);
  }

  @Test
  void an_account_without_the_subject_attribute_fails_as_missing_claims() throws Exception {
    OAuth2User user =
        new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("id", 7, "login", "al"), "id");
    OAuth2AuthenticationToken token =
        new OAuth2AuthenticationToken(user, AuthorityUtils.NO_AUTHORITIES, "plain");

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/signin?error=signin-failed");
    SignInFailed failed = onlyFailure();
    assertThat(failed.reason()).isEqualTo(SignInFailureReason.MISSING_CLAIMS);
    assertThat(failed.identityId()).isNull();
    assertThat(failed.credentialId()).isNull();
  }

  @Test
  void a_sign_in_whose_identity_is_gone_fails_as_an_unknown_credential() throws Exception {
    UUID gone = UUID.randomUUID();
    UUID credentialId = danglingCredential("alice", gone);
    OAuth2AuthenticationToken token =
        new OAuth2AuthenticationToken(oidcUser("alice"), AuthorityUtils.NO_AUTHORITIES, "idp");

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/signin?error=signin-failed");
    SignInFailed failed = onlyFailure();
    assertThat(failed.reason()).isEqualTo(SignInFailureReason.UNKNOWN_CREDENTIAL);
    assertThat(failed.identityId()).isEqualTo(gone);
    assertThat(failed.credentialId()).isEqualTo(credentialId);
  }

  @Test
  void a_link_to_an_account_whose_identity_is_gone_is_already_linked() throws Exception {
    UUID me = UUID.randomUUID();
    danglingCredential("alice", UUID.randomUUID());
    when(sessions.currentSession(request))
        .thenReturn(Optional.of(new WhoSession(me, UUID.randomUUID(), NOW)));
    OAuth2AuthenticationToken token =
        new LinkingAuthenticationToken(
            oidcUser("alice"), AuthorityUtils.NO_AUTHORITIES, "idp", new LinkIntent(me, NOW));

    handler.onAuthenticationSuccess(request, response, token);

    assertThat(response.getRedirectedUrl()).isEqualTo("/settings?error=already-linked");
    assertThat(events).isEmpty();
  }

  @Test
  void a_provider_sign_in_is_stamped_with_its_time() throws Exception {
    FederatedCredential credential = FederatedCredential.create(ISSUER, "alice");
    when(credentials.findByIssuerAndSubject(ISSUER, "alice")).thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(any()))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(Identity.create(), Set.of())));
    OAuth2AuthenticationToken token =
        new OAuth2AuthenticationToken(oidcUser("alice"), AuthorityUtils.NO_AUTHORITIES, "idp");

    handler.onAuthenticationSuccess(request, response, token);

    ArgumentCaptor<SecurityContext> saved = ArgumentCaptor.forClass(SecurityContext.class);
    verify(sessions).saveContext(saved.capture(), any(), any());
    WhoAuthenticationToken who = (WhoAuthenticationToken) saved.getValue().getAuthentication();
    assertThat(who.signInFactor())
        .hasValueSatisfying(
            factor -> {
              assertThat(factor.getAuthority())
                  .isEqualTo(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
              assertThat(factor.getIssuedAt()).isEqualTo(NOW);
            });
  }
}
