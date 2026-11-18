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
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import com.nimbusds.jose.util.JSONObjectUtils;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LinkStartFilterTest {

  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
  private static final Duration RECENT_SIGN_IN = Duration.ofMinutes(10);
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
  private final WhoService whoService = mock(WhoService.class);
  private final WhoSessions whoSessions =
      InMemorySessionRecordRepository.whoSessions(
          Clock.fixed(NOW, ZoneOffset.UTC), WhoEventPublisher.NOOP);
  private final WhoSessionSecurityContextRepository sessions =
      new WhoSessionSecurityContextRepository(
          whoService,
          whoSessions,
          Clock.fixed(NOW, ZoneOffset.UTC),
          WhoEventPublisher.NOOP,
          _ -> false);
  private final LinkStartFilter filter =
      new LinkStartFilter(
          WhoPaths.defaults(),
          new OidcAuthorizationRequests(
              new InMemoryClientRegistrationRepository(ACME),
              SignInRegistrations.of(List.of(ACME), Map.of()),
              "/who/oidc/authorize"),
          saved,
          sessions,
          new WhoExceptionHandoff(WhoPaths.defaults(), null),
          Clock.fixed(NOW, ZoneOffset.UTC),
          RECENT_SIGN_IN);
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
    return new MockHttpServletRequest("POST", "/who/me/accounts/" + registrationId);
  }

  private static void withWhoSession(MockHttpServletRequest request, Identity identity) {
    withWhoSession(request, identity, NOW);
  }

  private static void withWhoSession(
      MockHttpServletRequest request, Identity identity, Instant signedInAt) {
    request
        .getSession(true)
        .setAttribute(
            WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
            new WhoSession(identity.id(), identity.id(), signedInAt));
  }

  /** Signs {@code identity} in to this browser at {@code signedInAt}, loaded as the chain would. */
  private void browserSignIn(
      MockHttpServletRequest request, Identity identity, Instant signedInAt) {
    UUID credentialId = UUID.randomUUID();
    SessionRecord row =
        whoSessions.start(identity.id(), credentialId, SignInMethod.EMAIL_LINK, null, signedInAt);
    when(whoService.resolveDetailed(any()))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of())));
    request
        .getSession(true)
        .setAttribute(
            WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
            new WhoSession(
                identity.id(), credentialId, signedInAt, SignInMethod.EMAIL_LINK, row.id()));
    SecurityContextHolder.setContext(sessions.loadDeferredContext(request).get());
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
    MockHttpServletRequest request = start("acme");
    browserSignIn(request, bob, NOW);

    MockHttpServletResponse response = filter(request);

    assertThat(response.getRedirectedUrl()).startsWith("https://idp.example.com/authorize");
    assertThat(savedRequests(request)).hasSize(1);
  }

  @Test
  void asked_for_json_answers_where_to_go_instead_of_redirecting() throws Exception {
    MockHttpServletRequest request = start("acme");
    request.addHeader("Accept", "application/json");
    browserSignIn(request, bob, NOW);

    MockHttpServletResponse response = filter(request);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(JSONObjectUtils.parse(response.getContentAsString()).get("redirectUrl"))
        .asString()
        .isEqualTo(savedRequests(request).getFirst().getAuthorizationRequestUri())
        .startsWith("https://idp.example.com/authorize");
  }

  @Test
  void a_page_asking_for_html_first_is_still_redirected() throws Exception {
    MockHttpServletRequest request = start("acme");
    request.addHeader("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
    browserSignIn(request, bob, NOW);

    assertThat(filter(request).getRedirectedUrl()).startsWith("https://idp.example.com/authorize");
  }

  @Test
  void a_malformed_accept_header_is_treated_as_a_page_and_redirected() throws Exception {
    MockHttpServletRequest request = start("acme");
    request.addHeader("Accept", "not a media type");
    browserSignIn(request, bob, NOW);

    assertThat(filter(request).getRedirectedUrl()).startsWith("https://idp.example.com/authorize");
  }

  @Test
  void signed_in_just_within_the_window_goes_to_the_provider() throws Exception {
    MockHttpServletRequest request = start("acme");
    browserSignIn(request, bob, NOW.minus(RECENT_SIGN_IN).plusMillis(1));

    assertThat(filter(request).getRedirectedUrl()).startsWith("https://idp.example.com/authorize");
  }

  @Test
  void signed_in_longer_ago_than_the_window_is_401_and_starts_none() throws Exception {
    MockHttpServletRequest request = start("acme");
    browserSignIn(request, bob, NOW.minus(RECENT_SIGN_IN).minusSeconds(1));

    MockHttpServletResponse response = filter(request);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(savedRequests(request)).isEmpty();
  }

  @Test
  void signed_out_is_401() throws Exception {
    assertThat(filter(start("acme")).getStatus()).isEqualTo(401);
  }

  @Test
  void signed_in_without_a_browser_session_is_403_and_starts_none() throws Exception {
    signedIn(bob, SignInMethod.API_KEY);
    MockHttpServletRequest request = start("acme");

    MockHttpServletResponse response = filter(request);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("Who-Error")).isEqualTo("browser-session-required");
    assertThat(response.getRedirectedUrl()).isNull();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void an_api_key_beside_a_browser_session_for_someone_else_is_403() throws Exception {
    signedIn(bob, SignInMethod.API_KEY);
    MockHttpServletRequest request = start("acme");
    withWhoSession(request, Identity.create(IdentityStatus.ACTIVE));

    MockHttpServletResponse response = filter(request);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(savedRequests(request)).isEmpty();
  }

  @Test
  void an_unknown_registration_is_404() throws Exception {
    MockHttpServletRequest request = start("nope");
    browserSignIn(request, bob, NOW);

    assertThat(filter(request).getStatus()).isEqualTo(404);
  }

  @Test
  void old_path_is_not_served() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/who/oidc/link/acme");
    withWhoSession(request, Identity.create(IdentityStatus.ACTIVE));

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(savedRequests(request)).isEmpty();
  }

  @Test
  void any_other_request_passes_through() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/who/me/accounts/acme");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }
}
