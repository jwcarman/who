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

  /** Compact constructor: validates the settings and copies the origins. */
  public PasskeySettings {
    requireNonNull(rpId, "rpId must not be null");
    requireNonNull(rpName, "rpName must not be null");
    requireNonNull(allowedOrigins, "allowedOrigins must not be null");
    requireNonNull(registrationWindow, "registrationWindow must not be null");
    if (rpId.isBlank()) {
      throw new IllegalArgumentException("rpId must not be blank");
    }
    if (allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException("allowedOrigins must not be empty");
    }
    if (registrationWindow.isNegative() || registrationWindow.isZero()) {
      throw new IllegalArgumentException("registrationWindow must be positive");
    }
    allowedOrigins = Set.copyOf(allowedOrigins);
  }
}
