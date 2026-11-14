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

/**
 * A client registration who signs people in with, and the issuer its credentials are stored under.
 *
 * @param registrationId the Spring Security client registration id
 * @param issuer the issuer credentials from this registration are stored under
 * @param kind what kind of provider it is
 * @param subjectAttribute the user-info attribute that identifies the account; null for OpenID
 *     Connect, where it is the ID token's {@code sub}
 * @param trustEmail whether the provider's verified email may be used
 */
public record SignInRegistration(
    String registrationId, String issuer, Kind kind, String subjectAttribute, boolean trustEmail) {

  /** What kind of provider a registration signs in with. */
  public enum Kind {
    /** OpenID Connect: the account is the ID token's {@code iss} and {@code sub}. */
    OIDC,
    /** github.com: the account is the user's numeric {@code id}; emails come from its API. */
    GITHUB,
    /** Any other OAuth 2 provider: the account is a configured user-info attribute. */
    OAUTH2
  }

  /**
   * Whether this registration is OpenID Connect.
   *
   * @return {@code true} for {@link Kind#OIDC}
   */
  public boolean oidc() {
    return kind == Kind.OIDC;
  }
}
