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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LinkStartFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
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

  private final HttpSessionOAuth2AuthorizationRequestRepository saved =
      new HttpSessionOAuth2AuthorizationRequestRepository();
  private final LinkStartFilter filter =
      new LinkStartFilter(
          new OidcAuthorizationRequests(
              new InMemoryClientRegistrationRepository(ACME),
              SignInRegistrations.of(List.of(ACME), Map.of())),
          saved,
          new WhoSessionSecurityContextRepository(mock(WhoService.class), Clock.systemUTC()),
          new WhoExceptionHandoff(null),
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final Identity bob = Identity.create(IdentityStatus.ACTIVE);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private void signedIn(Identity identity, SignInMethod method) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(identity, Set.of()), identity.id(), method));
  }

  private static MockHttpServletRequest start(String registrationId) {
    return new MockHttpServletRequest("POST", "/who/oidc/link/" + registrationId);
  }

  private static void withWhoSession(MockHttpServletRequest request, Identity identity) {
    request
        .getSession(true)
        .setAttribute(
            WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
            new WhoSession(identity.id(), identity.id(), NOW));
  }

  private static List<OAuth2AuthorizationRequest> savedRequests(MockHttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return List.of();
    }
    return Collections.list(session.getAttributeNames()).stream()
        .map(session::getAttribute)
        .filter(OAuth2AuthorizationRequest.class::isInstance)
        .map(OAuth2AuthorizationRequest.class::cast)
        .toList();
  }

  private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void signed_in_in_this_browser_goes_to_the_provider() throws Exception {
    signedIn(bob, SignInMethod.EMAIL_LINK);
    MockHttpServletRequest request = start("acme");
    withWhoSession(request, bob);

    MockHttpServletResponse response = filter(request);

    assertThat(response.getRedirectedUrl()).startsWith("https://idp.example.com/authorize");
    assertThat(savedRequests(request)).hasSize(1);
  }

  @Test
  void signed_out_is_401() throws Exception {
    assertThat(filter(start("acme")).getStatus()).isEqualTo(401);
  }

  @Test
  void signed_in_without_a_browser_session_is_401_and_starts_none() throws Exception {
    signedIn(bob, SignInMethod.API_KEY);
    MockHttpServletRequest request = start("acme");

    MockHttpServletResponse response = filter(request);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void a_browser_session_for_someone_else_is_401() throws Exception {
    signedIn(bob, SignInMethod.API_KEY);
    MockHttpServletRequest request = start("acme");
    withWhoSession(request, Identity.create(IdentityStatus.ACTIVE));

    MockHttpServletResponse response = filter(request);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(savedRequests(request)).isEmpty();
  }

  @Test
  void an_unknown_registration_is_404() throws Exception {
    signedIn(bob, SignInMethod.EMAIL_LINK);
    MockHttpServletRequest request = start("nope");
    withWhoSession(request, bob);

    assertThat(filter(request).getStatus()).isEqualTo(404);
  }

  @Test
  void any_other_request_passes_through() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/who/oidc/link/acme");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }
}
