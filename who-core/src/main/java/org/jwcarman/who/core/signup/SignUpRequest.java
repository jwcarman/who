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
package org.jwcarman.who.core.signup;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Optional;

import org.jwcarman.who.core.event.SignInMethod;

/**
 * Someone who proved a credential who does not know, asking to create an identity with it.
 *
 * @param method how they signed in
 * @param registrationId the provider registration they used, such as {@code google}, or {@code
 *     null} for a method without one
 * @param issuer the issuer, as who's configuration names it
 * @param subject the account's subject at that issuer
 * @param verifiedEmail the email address, only when a provider trusted for email marks it verified
 * @param claims what the provider said about the account, read-only, such as {@code hd}
 */
public record SignUpRequest(
    SignInMethod method,
    String registrationId,
    String issuer,
    String subject,
    Optional<String> verifiedEmail,
    Map<String, Object> claims) {

  /** Validates the components and copies the claims. */
  public SignUpRequest {
    requireNonNull(method, "method must not be null");
    requireNonNull(issuer, "issuer must not be null");
    requireNonNull(subject, "subject must not be null");
    requireNonNull(verifiedEmail, "verifiedEmail must not be null");
    claims = Map.copyOf(requireNonNull(claims, "claims must not be null"));
  }
}
