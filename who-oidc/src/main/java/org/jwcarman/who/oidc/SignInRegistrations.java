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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.util.StringUtils;

/**
 * The client registrations who signs people in with, each with the issuer its credentials are
 * stored under. Only {@code authorization_code} registrations take part; one that cannot be signed
 * in with safely fails with {@link InvalidRegistrationException}.
 */
public final class SignInRegistrations {

  static final String GITHUB_ISSUER = "https://github.com";
  static final String GITHUB_SUBJECT_ATTRIBUTE = "id";
  static final String GOOGLE_ISSUER = "https://accounts.google.com";

  private static final String ENTRA_HOST = "login.microsoftonline.com";
  private static final String[] ENTRA_SHARED_TENANTS = {"common", "organizations", "consumers"};

  private final Map<String, SignInRegistration> registrations;

  private SignInRegistrations(Map<String, SignInRegistration> registrations) {
    this.registrations = Collections.unmodifiableMap(registrations);
  }

  /**
   * Reads the sign-in registrations.
   *
   * @param clientRegistrations the application's client registrations
   * @param settings {@code who.oidc.providers}, by registration id
   * @return the registrations who signs in with
   * @throws InvalidRegistrationException when one cannot be signed in with safely
   */
  public static SignInRegistrations of(
      Iterable<ClientRegistration> clientRegistrations, Map<String, ProviderSettings> settings) {
    Map<String, SignInRegistration> byId = new LinkedHashMap<>();
    Map<String, String> idByIssuer = new LinkedHashMap<>();
    for (ClientRegistration client : clientRegistrations) {
      ProviderSettings provider =
          settings.getOrDefault(client.getRegistrationId(), ProviderSettings.DEFAULTS);
      if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(client.getAuthorizationGrantType())
          || !provider.enabled()) {
        continue;
      }
      SignInRegistration registration = registration(client, provider);
      String other = idByIssuer.putIfAbsent(registration.issuer(), registration.registrationId());
      if (other != null) {
        throw new InvalidRegistrationException(
            registration.registrationId(),
            property(registration.registrationId(), "issuer"),
            "is "
                + registration.issuer()
                + ", the same issuer as client registration '"
                + other
                + "'; two registrations would sign the same accounts in");
      }
      byId.put(registration.registrationId(), registration);
    }
    return new SignInRegistrations(byId);
  }

  /**
   * Finds a registration by id.
   *
   * @param registrationId the client registration id
   * @return the registration, or empty when who does not sign in with it
   */
  public Optional<SignInRegistration> find(String registrationId) {
    return Optional.ofNullable(registrations.get(registrationId));
  }

  /**
   * Whether there are no registrations to sign in with.
   *
   * @return {@code true} when there are none
   */
  public boolean isEmpty() {
    return registrations.isEmpty();
  }

  /**
   * Every registration, in the order the application declared them.
   *
   * @return the registrations
   */
  public Collection<SignInRegistration> all() {
    return registrations.values();
  }

  private static SignInRegistration registration(
      ClientRegistration client, ProviderSettings provider) {
    String id = client.getRegistrationId();
    if (client.getScopes().contains(OidcScopes.OPENID)) {
      String issuer = oidcIssuer(client, provider);
      rejectSharedEntraTenant(id, issuer);
      return new SignInRegistration(
          id,
          issuer,
          SignInRegistration.Kind.OIDC,
          null,
          trustEmail(provider, GOOGLE_ISSUER.equals(issuer)));
    }
    if (isGitHub(client) && provider.issuer() == null && provider.subjectAttribute() == null) {
      return new SignInRegistration(
          id,
          GITHUB_ISSUER,
          SignInRegistration.Kind.GITHUB,
          GITHUB_SUBJECT_ATTRIBUTE,
          trustEmail(provider, true));
    }
    String issuer = required(id, provider.issuer(), "issuer");
    String subjectAttribute = required(id, provider.subjectAttribute(), "subject-attribute");
    rejectSharedEntraTenant(id, issuer);
    return new SignInRegistration(
        id,
        issuer,
        isGitHub(client) ? SignInRegistration.Kind.GITHUB : SignInRegistration.Kind.OAUTH2,
        subjectAttribute,
        trustEmail(provider, false));
  }

  private static String oidcIssuer(ClientRegistration client, ProviderSettings provider) {
    String id = client.getRegistrationId();
    String discovered = client.getProviderDetails().getIssuerUri();
    if (StringUtils.hasText(discovered)) {
      if (provider.issuer() != null && !provider.issuer().equals(discovered)) {
        throw new InvalidRegistrationException(
            id,
            property(id, "issuer"),
            "is "
                + provider.issuer()
                + " but the provider's issuer-uri is "
                + discovered
                + "; remove it, or make the two the same");
      }
      return discovered;
    }
    return required(id, provider.issuer(), "issuer");
  }

  private static String required(String id, String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw new InvalidRegistrationException(
          id,
          property(id, name),
          "is not set, and the provider has no issuer-uri that would give who a default");
    }
    return value;
  }

  private static void rejectSharedEntraTenant(String id, String issuer) {
    URI uri = URI.create(issuer);
    if (!ENTRA_HOST.equalsIgnoreCase(uri.getHost()) || uri.getPath() == null) {
      return;
    }
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    for (String tenant : ENTRA_SHARED_TENANTS) {
      if (path.equals("/" + tenant) || path.startsWith("/" + tenant + "/")) {
        throw new InvalidRegistrationException(
            id,
            "spring.security.oauth2.client.provider." + id + ".issuer-uri",
            "is "
                + issuer
                + ", Microsoft Entra's shared '"
                + tenant
                + "' endpoint, whose tokens carry each tenant's own issuer; use your tenant's"
                + " issuer, https://login.microsoftonline.com/<tenant-id>/v2.0");
      }
    }
  }

  private static boolean trustEmail(ProviderSettings provider, boolean byDefault) {
    return provider.trustEmail() != null ? provider.trustEmail() : byDefault;
  }

  private static boolean isGitHub(ClientRegistration client) {
    return "github.com".equalsIgnoreCase(host(client.getProviderDetails().getAuthorizationUri()))
        && "api.github.com"
            .equalsIgnoreCase(host(client.getProviderDetails().getUserInfoEndpoint().getUri()));
  }

  private static String host(String uri) {
    return StringUtils.hasText(uri) ? URI.create(uri).getHost() : null;
  }

  private static String property(String id, String name) {
    return "who.oidc.providers." + id + "." + name;
  }
}
