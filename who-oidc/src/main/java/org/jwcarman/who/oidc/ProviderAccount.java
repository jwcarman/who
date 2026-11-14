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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * The provider account a sign-in came back with.
 *
 * @param registration the registration it signed in through
 * @param subject the account's identifier at the provider, stable for the life of the account
 * @param displayName what to call the account on the confirm screen
 * @param verifiedEmail the account's email, only when the provider is trusted for email and marks
 *     it verified
 * @param claims the ID token's simple-valued claims (text, true/false and numbers), for the sign-up
 *     policy; empty for a provider that is not OpenID Connect
 */
public record ProviderAccount(
    SignInRegistration registration,
    String subject,
    String displayName,
    Optional<String> verifiedEmail,
    Map<String, Object> claims) {

  /** Copies the claims and checks that nothing is missing. */
  public ProviderAccount {
    Objects.requireNonNull(registration, "registration");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(verifiedEmail, "verifiedEmail");
    claims = Map.copyOf(claims);
  }

  /**
   * Reads the account from what Spring Security signed in.
   *
   * @param user the provider's user
   * @param registration the registration it signed in through
   * @return the account
   * @throws IllegalStateException when the provider did not return the subject attribute
   */
  public static ProviderAccount from(OAuth2User user, SignInRegistration registration) {
    if (user instanceof OidcUser oidcUser) {
      return fromOidc(oidcUser, registration);
    }
    Object id = user.getAttribute(registration.subjectAttribute());
    if (id == null) {
      throw new IllegalStateException(
          "The provider of client registration '"
              + registration.registrationId()
              + "' returned no '"
              + registration.subjectAttribute()
              + "' attribute to identify the account");
    }
    String subject = String.valueOf(id);
    String login = user.getAttribute("login");
    Optional<String> email =
        registration.trustEmail()
            ? Optional.ofNullable(user.<String>getAttribute(WhoOAuth2UserService.VERIFIED_EMAIL))
            : Optional.empty();
    return new ProviderAccount(
        registration, subject, login != null ? login : subject, email, Map.of());
  }

  private static ProviderAccount fromOidc(OidcUser user, SignInRegistration registration) {
    String subject = user.getSubject();
    String displayName = firstNonNull(user.getEmail(), user.getPreferredUsername(), subject);
    Optional<String> email =
        registration.trustEmail() && Boolean.TRUE.equals(user.getEmailVerified())
            ? Optional.ofNullable(user.getEmail())
            : Optional.empty();
    Map<String, Object> claims = new LinkedHashMap<>();
    user.getIdToken()
        .getClaims()
        .forEach(
            (name, value) -> {
              if (value instanceof String || value instanceof Boolean || value instanceof Number) {
                claims.put(name, value);
              }
            });
    return new ProviderAccount(registration, subject, displayName, email, claims);
  }

  private static String firstNonNull(String first, String second, String third) {
    if (first != null) {
      return first;
    }
    return second != null ? second : third;
  }
}
