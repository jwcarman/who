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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The application's registrations, each one who signs in with sending the provider back to who's
 * callback: {@code <root>/oidc/callback/{registrationId}}. Such a registration left at Spring
 * Security's default redirect URI ({@value #SPRING_DEFAULT}, which {@code CommonOAuth2Provider} and
 * an {@code issuer-uri} registration get) is pointed at who's callback; any other is used as
 * written, and {@link #problem} says when who cannot serve it.
 */
public final class CallbackRegistrations
    implements ClientRegistrationRepository, Iterable<ClientRegistration> {

  /** Spring Security's default redirect URI for a sign-in registration. */
  public static final String SPRING_DEFAULT = "{baseUrl}/{action}/oauth2/code/{registrationId}";

  private final ClientRegistrationRepository delegate;
  private final SignInRegistrations signIn;
  private final String callbackTemplate;

  /**
   * Wraps the application's registrations.
   *
   * @param delegate the application's registrations
   * @param signIn the registrations who signs in with; only these are pointed at who's callback
   * @param paths where who's callback lives
   */
  public CallbackRegistrations(
      ClientRegistrationRepository delegate, SignInRegistrations signIn, WhoPaths paths) {
    this.delegate = requireNonNull(delegate, "delegate must not be null");
    this.signIn = requireNonNull(signIn, "signIn must not be null");
    this.callbackTemplate = "{baseUrl}" + callbackPath(paths);
  }

  /**
   * Who's callback path, with Spring's {@code {registrationId}} placeholder.
   *
   * @param paths where who's endpoints live
   * @return the path, such as {@code /who/oidc/callback/{registrationId}}
   */
  public static String callbackPath(WhoPaths paths) {
    return paths.of("/oidc/callback/{registrationId}");
  }

  /**
   * Says why who cannot serve a registration's redirect URI, if it cannot: an explicit one must end
   * with who's callback path.
   *
   * @param registration the registration
   * @param paths where who's endpoints live
   * @return the problem, or empty when who serves the redirect URI
   */
  public static Optional<String> problem(ClientRegistration registration, WhoPaths paths) {
    String uri = registration.getRedirectUri();
    String callback = callbackPath(paths);
    String literal = paths.of("/oidc/callback/" + registration.getRegistrationId());
    if (SPRING_DEFAULT.equals(uri) || uri.endsWith(callback) || uri.endsWith(literal)) {
      return Optional.empty();
    }
    return Optional.of("is " + uri + ", which who does not serve");
  }

  @Override
  public ClientRegistration findByRegistrationId(String registrationId) {
    ClientRegistration registration = delegate.findByRegistrationId(registrationId);
    return registration == null ? null : pointed(registration);
  }

  /** Every registration, as {@link #findByRegistrationId} gives it; none when none are listed. */
  @Override
  public Iterator<ClientRegistration> iterator() {
    List<ClientRegistration> all = new ArrayList<>();
    if (delegate instanceof Iterable<?> registrations) {
      registrations.forEach(
          registration -> {
            if (registration instanceof ClientRegistration client) {
              all.add(pointed(client));
            }
          });
    }
    return all.iterator();
  }

  private ClientRegistration pointed(ClientRegistration registration) {
    // a registration who does not sign in with is the application's, for its own oauth2Client()
    if (!SPRING_DEFAULT.equals(registration.getRedirectUri())
        || signIn.find(registration.getRegistrationId()).isEmpty()) {
      return registration;
    }
    return ClientRegistration.withClientRegistration(registration)
        .redirectUri(callbackTemplate)
        .build();
  }
}
