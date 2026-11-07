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
package org.jwcarman.who.passkey;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Set;

/**
 * The relying party's settings, from {@code who.passkey.*}.
 *
 * @param rpId the relying party id: the app's own host
 * @param rpName the relying party name authenticators show
 * @param allowedOrigins the origins a registration or sign-in response may come from
 * @param registrationWindow how recently the session must have signed in to register a passkey
 */
public record PasskeySettings(
    String rpId, String rpName, Set<String> allowedOrigins, Duration registrationWindow) {

  /**
   * Compact constructor: validates the settings and copies the origins.
   *
   * @throws IllegalArgumentException if {@code rpId} or {@code rpName} is blank, there are no
   *     origins, an origin is not an absolute http(s) origin (scheme, host and optional port, no
   *     path), or the window is not positive
   */
  public PasskeySettings {
    requireNonNull(rpId, "rpId must not be null");
    requireNonNull(rpName, "rpName must not be null");
    requireNonNull(allowedOrigins, "allowedOrigins must not be null");
    requireNonNull(registrationWindow, "registrationWindow must not be null");
    if (rpId.isBlank()) {
      throw new IllegalArgumentException("rpId must not be blank");
    }
    if (rpName.isBlank()) {
      throw new IllegalArgumentException("rpName (who.passkey.rp-name) must not be blank");
    }
    if (allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("allowedOrigins must not be empty");
    }
    allowedOrigins.forEach(PasskeySettings::requireOrigin);
    if (registrationWindow.isNegative() || registrationWindow.isZero()) {
      throw new IllegalArgumentException("registrationWindow must be positive");
    }
    allowedOrigins = Set.copyOf(allowedOrigins);
  }

  /** An origin is an absolute http(s) URL with a host, an optional port and nothing else. */
  private static void requireOrigin(String origin) {
    requireNonNull(origin, "allowedOrigins must not contain null");
    if (!isOrigin(origin)) {
      throw new IllegalArgumentException(
          "allowedOrigins (who.passkey.allowed-origins) must be origins such as"
              + " https://app.example.com or http://localhost:8080: a scheme of http or https, a"
              + " host and an optional port, with no path; got '"
              + origin
              + "'");
    }
  }

  private static boolean isOrigin(String origin) {
    URI uri;
    try {
      uri = new URI(origin);
    } catch (URISyntaxException _) {
      return false;
    }
    String scheme = uri.getScheme();
    return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
        && uri.getHost() != null
        && uri.getRawUserInfo() == null
        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
        && uri.getRawQuery() == null
        && uri.getRawFragment() == null;
  }
}
