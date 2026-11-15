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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Builds the authorization requests who sends people to a provider with: a plain sign-in, only for
 * the registrations who signs in with, and a link, which carries its {@link LinkIntent} in the
 * request's attributes (kept on the server, never sent) and, for an OpenID Connect provider, asks
 * for a fresh login.
 */
final class OidcAuthorizationRequests implements OAuth2AuthorizationRequestResolver {

  private static final String BASE_URI =
      OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
  private static final String REGISTRATION_ID = "registrationId";

  private final ClientRegistrationRepository clients;
  private final SignInRegistrations registrations;
  private final DefaultOAuth2AuthorizationRequestResolver plain;
  private final RequestMatcher matcher =
      PathPatternRequestMatcher.withDefaults().matcher(BASE_URI + "/{" + REGISTRATION_ID + "}");

  OidcAuthorizationRequests(
      ClientRegistrationRepository clients, SignInRegistrations registrations) {
    this.clients = clients;
    this.registrations = registrations;
    this.plain = new DefaultOAuth2AuthorizationRequestResolver(clients, BASE_URI);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    RequestMatcher.MatchResult match = matcher.matcher(request);
    if (!match.isMatch()) {
      return null;
    }
    // the one-argument form, which builds the sign-in ("login") redirect URI
    return registrations
        .find(match.getVariables().get(REGISTRATION_ID))
        .map(registration -> plain.resolve(request))
        .orElse(null);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(
      HttpServletRequest request, String clientRegistrationId) {
    return registrations
        .find(clientRegistrationId)
        .map(registration -> plain.resolve(request, clientRegistrationId))
        .orElse(null);
  }

  /**
   * Builds the request that links a provider account to {@code identityId}.
   *
   * @param request the request starting the link
   * @param registrationId the provider registration
   * @param identityId the identity signed in, which the account will be linked to
   * @param now when the link starts; a fresh login must be no older
   * @return the request, or empty for a registration who does not sign in with
   */
  Optional<OAuth2AuthorizationRequest> link(
      HttpServletRequest request, String registrationId, UUID identityId, Instant now) {
    return registrations
        .find(registrationId)
        .map(
            registration -> {
              DefaultOAuth2AuthorizationRequestResolver resolver =
                  new DefaultOAuth2AuthorizationRequestResolver(clients, BASE_URI);
              LinkIntent intent = new LinkIntent(identityId, now);
              resolver.setAuthorizationRequestCustomizer(
                  builder ->
                      builder
                          .attributes(intent::writeTo)
                          .additionalParameters(p -> p.putAll(linkParameters(registration))));
              // the sign-in callback: Boot's default redirect URI has an {action}, which the
              // two-argument resolve would fill with "authorize"
              return resolver.resolve(new SignInAction(request), registrationId);
            });
  }

  /**
   * What a link asks the provider for. A fresh login: {@code max_age=0} obliges the provider to
   * report {@code auth_time}, and {@code prompt=login} asks one that skips its login screen anyway
   * to show it. Otherwise, where the provider supports it, a choice of account, so a remembered
   * account is not linked without the person seeing which one it is.
   */
  private static Map<String, Object> linkParameters(SignInRegistration registration) {
    if (registration.freshLogin()) {
      return Map.of("max_age", "0", "prompt", "login");
    }
    if (registration.oidc() || registration.kind() == SignInRegistration.Kind.GITHUB) {
      return Map.of("prompt", "select_account");
    }
    return Map.of();
  }

  /** The request, saying the redirect URI's {@code {action}} is a sign-in. */
  private static final class SignInAction extends HttpServletRequestWrapper {

    SignInAction(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getParameter(String name) {
      return "action".equals(name) ? "login" : super.getParameter(name);
    }
  }
}
