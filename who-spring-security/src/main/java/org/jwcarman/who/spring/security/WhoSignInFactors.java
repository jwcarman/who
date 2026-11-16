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
package org.jwcarman.who.spring.security;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/**
 * The Spring Security factor each way of signing in stands for, stamped with when the person signed
 * in. An API key stands for no sign-in, so it has no factor.
 *
 * <p>Spring Security stamps its own factors with the time of the request that presented them; who
 * stamps them with the time the person signed in, so that a factor's age says how long ago that
 * was.
 */
public final class WhoSignInFactors {

  /** The authorities of the factors who stamps. */
  public static final List<String> AUTHORITIES =
      List.of(
          FactorGrantedAuthority.OTT_AUTHORITY,
          FactorGrantedAuthority.WEBAUTHN_AUTHORITY,
          FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY,
          FactorGrantedAuthority.BEARER_AUTHORITY);

  private WhoSignInFactors() {}

  /**
   * The factor for a sign-in.
   *
   * @param method how the person signed in
   * @param signedInAt when they signed in
   * @return the factor, or empty for an API key
   */
  public static Optional<FactorGrantedAuthority> of(SignInMethod method, Instant signedInAt) {
    requireNonNull(signedInAt, "signedInAt must not be null");
    return authorityOf(requireNonNull(method, "method must not be null"))
        .map(
            authority ->
                FactorGrantedAuthority.withAuthority(authority).issuedAt(signedInAt).build());
  }

  /**
   * Whether a sign-in at {@code signedInAt} is within {@code window} of {@code now}. A sign-in
   * exactly {@code window} ago no longer is, as Spring Security's factor check has it.
   *
   * @param signedInAt when the person signed in
   * @param window how long a sign-in stays recent
   * @param now the current time
   * @return whether the sign-in is recent
   */
  public static boolean isRecent(Instant signedInAt, Duration window, Instant now) {
    return now.isBefore(signedInAt.plus(window));
  }

  private static Optional<String> authorityOf(SignInMethod method) {
    return switch (method) {
      case EMAIL_LINK, EMAIL_CODE -> Optional.of(FactorGrantedAuthority.OTT_AUTHORITY);
      case PASSKEY -> Optional.of(FactorGrantedAuthority.WEBAUTHN_AUTHORITY);
      case OIDC -> Optional.of(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
      case JWT -> Optional.of(FactorGrantedAuthority.BEARER_AUTHORITY);
      case API_KEY -> Optional.empty();
    };
  }
}
