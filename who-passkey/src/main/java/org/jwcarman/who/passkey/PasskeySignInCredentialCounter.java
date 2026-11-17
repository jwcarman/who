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

import java.util.UUID;

import org.jwcarman.who.core.service.SignInCredentialCounter;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Counts an identity's passkeys, for the last-credential rule. */
public class PasskeySignInCredentialCounter implements SignInCredentialCounter {

  private final JdbcClient jdbcClient;

  /**
   * Creates the counter.
   *
   * @param jdbcClient the database
   */
  public PasskeySignInCredentialCounter(JdbcClient jdbcClient) {
    this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient must not be null");
  }

  @Override
  public String kind() {
    return "passkey";
  }

  @Override
  public long count(UUID identityId) {
    return jdbcClient
        .sql(
            """
            SELECT COUNT(*) FROM who_passkey_credential c JOIN who_credential_identity ci ON ci.credential_id = c.credential_uuid WHERE ci.identity_id = :identityId
            """)
        .param("identityId", identityId)
        .query(Long.class)
        .single();
  }
}
