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
package org.jwcarman.who.emaillink;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** JDBC implementation of {@link EmailLinkTokenRepository} on {@code who_email_link_token}. */
public class JdbcEmailLinkTokenRepository implements EmailLinkTokenRepository {

  private static final String PARAM_CREDENTIAL_ID = "credentialId";
  private static final String PARAM_HASH = "hash";
  private static final String PARAM_NOW = "now";

  private final JdbcClient jdbcClient;

  /**
   * Creates a new repository.
   *
   * @param jdbcClient the JDBC client used to execute queries
   */
  public JdbcEmailLinkTokenRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public boolean issue(
      UUID credentialId,
      String tokenHash,
      String codeHash,
      Instant now,
      Instant expiresAt,
      Duration resendInterval) {
    return jdbcClient
            .sql(
                """
                INSERT INTO who_email_link_token (credential_id, token_hash, code_hash, code_attempts, created_at, expires_at)
                VALUES (:credentialId, :hash, :codeHash, 0, :now, :expiresAt)
                ON CONFLICT (credential_id) DO UPDATE
                   SET token_hash = EXCLUDED.token_hash,
                       code_hash = EXCLUDED.code_hash,
                       code_attempts = 0,
                       created_at = EXCLUDED.created_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE who_email_link_token.created_at <= :cutoff
                """)
            .param(PARAM_CREDENTIAL_ID, credentialId)
            .param(PARAM_HASH, tokenHash)
            .param("codeHash", codeHash)
            .param(PARAM_NOW, now.atOffset(ZoneOffset.UTC))
            .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
            .param("cutoff", now.minus(resendInterval).atOffset(ZoneOffset.UTC))
            .update()
        > 0;
  }

  @Override
  public Optional<UUID> consume(String tokenHash, Instant now) {
    return jdbcClient
        .sql(
            """
            DELETE FROM who_email_link_token
             WHERE token_hash = :hash AND expires_at > :now
            RETURNING credential_id
            """)
        .param(PARAM_HASH, tokenHash)
        .param(PARAM_NOW, now.atOffset(ZoneOffset.UTC))
        .query((rs, rowNum) -> rs.getObject("credential_id", UUID.class))
        .optional();
  }

  @Override
  public Optional<EmailCodeAttempt> attemptCode(
      String normalizedAddress, int maxAttempts, Instant now) {
    return jdbcClient
        .sql(
            """
            UPDATE who_email_link_token t
               SET code_attempts = t.code_attempts + 1
              FROM who_email_credential c
             WHERE c.address = :address AND t.credential_id = c.id
               AND t.code_attempts < :maxAttempts AND t.expires_at > :now
            RETURNING t.credential_id, t.code_hash
            """)
        .param("address", normalizedAddress)
        .param("maxAttempts", maxAttempts)
        .param(PARAM_NOW, now.atOffset(ZoneOffset.UTC))
        .query(
            (rs, rowNum) ->
                new EmailCodeAttempt(
                    rs.getObject("credential_id", UUID.class), rs.getString("code_hash")))
        .optional();
  }

  @Override
  public Optional<UUID> consumeCode(UUID credentialId, String codeHash, Instant now) {
    return jdbcClient
        .sql(
            """
            DELETE FROM who_email_link_token
             WHERE credential_id = :credentialId AND code_hash = :hash AND expires_at > :now
            RETURNING credential_id
            """)
        .param(PARAM_CREDENTIAL_ID, credentialId)
        .param(PARAM_HASH, codeHash)
        .param(PARAM_NOW, now.atOffset(ZoneOffset.UTC))
        .query((rs, rowNum) -> rs.getObject("credential_id", UUID.class))
        .optional();
  }

  @Override
  public void delete(UUID credentialId, String tokenHash) {
    jdbcClient
        .sql(
            "DELETE FROM who_email_link_token"
                + " WHERE credential_id = :credentialId AND token_hash = :hash")
        .param(PARAM_CREDENTIAL_ID, credentialId)
        .param(PARAM_HASH, tokenHash)
        .update();
  }
}
