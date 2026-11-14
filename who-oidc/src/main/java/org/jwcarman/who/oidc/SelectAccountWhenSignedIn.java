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

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Starts provider sign-in only for the registrations who signs in with, and asks an OpenID Connect
 * provider to let the person choose the account ({@code prompt=select_account}) when a who session
 * is signed in, since that sign-in links an account.
 */
final class SelectAccountWhenSignedIn implements OAuth2AuthorizationRequestResolver {

  private static final String REGISTRATION_ID = "registrationId";

  private final SignInRegistrations registrations;
  private final WhoSessionSecurityContextRepository sessions;
  private final DefaultOAuth2AuthorizationRequestResolver plain;
  private final DefaultOAuth2AuthorizationRequestResolver selectAccount;
  private final RequestMatcher matcher =
      PathPatternRequestMatcher.withDefaults()
          .matcher(
              OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                  + "/{"
                  + REGISTRATION_ID
                  + "}");

  SelectAccountWhenSignedIn(
      ClientRegistrationRepository clients,
      SignInRegistrations registrations,
      WhoSessionSecurityContextRepository sessions) {
    this.registrations = registrations;
    this.sessions = sessions;
    String baseUri =
        OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
    this.plain = new DefaultOAuth2AuthorizationRequestResolver(clients, baseUri);
    this.selectAccount = new DefaultOAuth2AuthorizationRequestResolver(clients, baseUri);
    this.selectAccount.setAuthorizationRequestCustomizer(
        builder -> builder.additionalParameters(p -> p.put("prompt", "select_account")));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    RequestMatcher.MatchResult match = matcher.matcher(request);
    if (!match.isMatch()) {
      return null;
    }
    // the one-argument form, which builds the sign-in ("login") redirect URI
    return delegate(request, match.getVariables().get(REGISTRATION_ID))
        .map(resolver -> resolver.resolve(request))
        .orElse(null);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(
      HttpServletRequest request, String clientRegistrationId) {
    return delegate(request, clientRegistrationId)
        .map(resolver -> resolver.resolve(request, clientRegistrationId))
        .orElse(null);
  }

  private Optional<DefaultOAuth2AuthorizationRequestResolver> delegate(
      HttpServletRequest request, String clientRegistrationId) {
    return registrations
        .find(clientRegistrationId)
        .map(
            registration ->
                registration.oidc() && sessions.currentSession(request).isPresent()
                    ? selectAccount
                    : plain);
  }
}
