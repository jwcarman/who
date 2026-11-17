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

import java.util.List;
import java.util.UUID;

import org.jwcarman.who.core.service.SignInCredentialCounter;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Counts an identity's linked provider accounts that a configured sign-in registration signs in,
 * for the last-credential rule. An account whose issuer no registration in {@link
 * SignInRegistrations} has (a provider removed from the configuration, or an issuer and subject a
 * JWT signs in with) is not counted: no one can sign in with it at {@code /who/oidc}. Without
 * {@code who-oidc} no linked account is counted at all.
 */
public class OidcSignInCredentialCounter implements SignInCredentialCounter {

  private final JdbcClient jdbcClient;
  private final List<String> issuers;

  /**
   * Creates the counter.
   *
   * @param jdbcClient the database
   * @param registrations the registrations who signs in with; their issuers are the ones counted
   */
  public OidcSignInCredentialCounter(JdbcClient jdbcClient, SignInRegistrations registrations) {
    this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient must not be null");
    this.issuers =
        requireNonNull(registrations, "registrations must not be null").all().stream()
            .map(SignInRegistration::issuer)
            .toList();
  }

  @Override
  public String kind() {
    return "account";
  }

  @Override
  public long count(UUID identityId) {
    if (issuers.isEmpty()) {
      return 0;
    }
    return jdbcClient
        .sql(
            """
            SELECT COUNT(*) FROM who_federated_credential c JOIN who_credential_identity ci ON ci.credential_id = c.id WHERE ci.identity_id = :identityId AND c.issuer IN (:issuers)
            """)
        .param("identityId", identityId)
        .param("issuers", issuers)
        .query(Long.class)
        .single();
  }
}
