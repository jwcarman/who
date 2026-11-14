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
 * What {@code who.oidc.providers.<registration-id>} says about one client registration.
 *
 * @param enabled {@code false} leaves the registration out of sign-in
 * @param issuer the issuer who stores credentials under; required for a provider without discovery
 *     ({@code issuer-uri}), and must equal {@code issuer-uri} when both are set; may be null
 * @param subjectAttribute the user-info attribute that identifies the account, for a provider that
 *     is not OpenID Connect; may be null
 * @param trustEmail whether the provider's verified email may be used; null takes the default
 */
public record ProviderSettings(
    boolean enabled, String issuer, String subjectAttribute, Boolean trustEmail) {

  /** Settings for a registration with nothing configured. */
  public static final ProviderSettings DEFAULTS = new ProviderSettings(true, null, null, null);
}
