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

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.signup.SignUpRequest;

/**
 * A provider account who does not know yet, waiting in the session for the person to confirm a
 * sign-up or a link. Single use; it expires.
 *
 * @param registrationId the client registration it signed in through
 * @param issuer the issuer its credential would be stored under
 * @param subject the account's identifier at the provider
 * @param displayName what to call the account on the confirm screen
 * @param verifiedEmail the verified email, or null
 * @param claims the ID token's simple-valued claims
 * @param createdFor the identity signed in when it was created, or null when signed out
 * @param expiresAt when it stops being usable
 */
public record PendingSignUp(
    String registrationId,
    String issuer,
    String subject,
    String displayName,
    String verifiedEmail,
    Map<String, Serializable> claims,
    UUID createdFor,
    Instant expiresAt)
    implements Serializable {

  /** Checks the required components and copies the claims. */
  public PendingSignUp {
    requireNonNull(registrationId, "registrationId must not be null");
    requireNonNull(issuer, "issuer must not be null");
    requireNonNull(subject, "subject must not be null");
    requireNonNull(displayName, "displayName must not be null");
    requireNonNull(expiresAt, "expiresAt must not be null");
    claims = Map.copyOf(claims);
  }

  /**
   * Starts a pending sign-up for an account.
   *
   * @param account the provider account
   * @param createdFor the identity signed in now, or null
   * @param expiresAt when it stops being usable
   * @return the pending sign-up
   */
  public static PendingSignUp of(ProviderAccount account, UUID createdFor, Instant expiresAt) {
    Map<String, Serializable> claims = new HashMap<>();
    account.claims().forEach((name, value) -> claims.put(name, (Serializable) value));
    return new PendingSignUp(
        account.registration().registrationId(),
        account.registration().issuer(),
        account.subject(),
        account.displayName(),
        account.verifiedEmail().orElse(null),
        claims,
        createdFor,
        expiresAt);
  }

  /**
   * The request the sign-up policy decides on.
   *
   * @return the request
   */
  public SignUpRequest toRequest() {
    return new SignUpRequest(
        SignInMethod.OIDC,
        registrationId,
        issuer,
        subject,
        Optional.ofNullable(verifiedEmail),
        Map.copyOf(claims));
  }
}
