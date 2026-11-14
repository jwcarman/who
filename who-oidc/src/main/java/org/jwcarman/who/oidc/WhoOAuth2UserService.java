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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;

import com.nimbusds.jose.util.JSONArrayUtils;

/**
 * Loads the provider's user with Spring Security's {@link DefaultOAuth2UserService}, and for GitHub
 * adds the primary, verified email from {@code /user/emails}, which only this step can read: it has
 * the access token. The token is not kept. Reading the emails needs the {@code user:email} scope;
 * without it, or when the call fails or takes longer than three seconds, the user signs in with no
 * email.
 */
public final class WhoOAuth2UserService
    implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  /** The attribute that carries GitHub's primary, verified email. */
  public static final String VERIFIED_EMAIL = "who_verified_email";

  private static final Logger log = LoggerFactory.getLogger(WhoOAuth2UserService.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(3);
  private static final String USER_EMAIL_SCOPE = "user:email";

  private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
  private final Function<String, Optional<SignInRegistration>> registrations;
  private final RestClient restClient;

  /**
   * Creates the service.
   *
   * @param registrations finds the sign-in registration for a client registration id, such as
   *     {@link SignInRegistrations#find}
   */
  public WhoOAuth2UserService(Function<String, Optional<SignInRegistration>> registrations) {
    this.registrations = registrations;
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    requestFactory.setReadTimeout(TIMEOUT);
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) {
    OAuth2User user = delegate.loadUser(request);
    boolean gitHub =
        registrations
            .apply(request.getClientRegistration().getRegistrationId())
            .map(registration -> registration.kind() == SignInRegistration.Kind.GITHUB)
            .orElse(false);
    if (!gitHub || !grantsUserEmail(request.getAccessToken().getScopes())) {
      return user;
    }
    return verifiedEmail(request).map(email -> withEmail(user, email, request)).orElse(user);
  }

  /** GitHub reports granted scopes comma-separated, which Spring reads as one scope. */
  private static boolean grantsUserEmail(Set<String> scopes) {
    return scopes.stream()
        .flatMap(scope -> Set.of(scope.split(",")).stream())
        .map(String::trim)
        .anyMatch(USER_EMAIL_SCOPE::equals);
  }

  private Optional<String> verifiedEmail(OAuth2UserRequest request) {
    try {
      URI userInfo =
          URI.create(
              request.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUri());
      URI emails =
          new URI(userInfo.getScheme(), userInfo.getAuthority(), "/user/emails", null, null);
      String body =
          restClient
              .get()
              .uri(emails)
              .header(
                  HttpHeaders.AUTHORIZATION, "Bearer " + request.getAccessToken().getTokenValue())
              .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
              .retrieve()
              .body(String.class);
      return JSONArrayUtils.parse(body).stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .filter(entry -> Boolean.TRUE.equals(entry.get("primary")))
          .filter(entry -> Boolean.TRUE.equals(entry.get("verified")))
          .map(entry -> entry.get("email"))
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .findFirst();
    } catch (RuntimeException | URISyntaxException | ParseException e) {
      log.debug(
          "GitHub's /user/emails could not be read; signing in without an email: {}", e.toString());
      return Optional.empty();
    }
  }

  private static OAuth2User withEmail(OAuth2User user, String email, OAuth2UserRequest request) {
    Map<String, Object> attributes = new HashMap<>(user.getAttributes());
    attributes.put(VERIFIED_EMAIL, email);
    return new DefaultOAuth2User(
        user.getAuthorities(),
        attributes,
        request
            .getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUserNameAttributeName());
  }
}
