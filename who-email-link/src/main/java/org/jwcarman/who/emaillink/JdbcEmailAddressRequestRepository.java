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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC implementation of {@link EmailAddressRequestRepository} on {@code
 * who_email_address_request}.
 */
public class JdbcEmailAddressRequestRepository implements EmailAddressRequestRepository {

  private static final String RETURNING =
      " RETURNING id, identity_id, address, replaces_credential_id, created_at, expires_at";
  private static final String PARAM_IDENTITY_ID = "identityId";
  private static final String PARAM_HASH = "hash";
  private static final String PARAM_NOW = "now";

  private final JdbcClient jdbcClient;

  /**
   * Creates a new repository.
   *
   * @param jdbcClient the JDBC client used to execute queries
   */
  public JdbcEmailAddressRequestRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public boolean save(
      EmailAddressRequest request, String tokenHash, String codeHash, Duration resendInterval) {
    return jdbcClient
            .sql(
                """
                INSERT INTO who_email_address_request
                       (id, identity_id, address, replaces_credential_id, token_hash, code_hash,
                        code_attempts, created_at, expires_at)
                SELECT :id, :identityId, :address, :replaces, :tokenHash, :codeHash, 0, :now, :expiresAt
                 WHERE NOT EXISTS (SELECT 1 FROM who_email_address_request
                                    WHERE address = :address AND identity_id <> :identityId
                                      AND created_at > :cutoff)
                ON CONFLICT (identity_id) DO UPDATE
                   SET id = EXCLUDED.id,
                       address = EXCLUDED.address,
                       replaces_credential_id = EXCLUDED.replaces_credential_id,
                       token_hash = EXCLUDED.token_hash,
                       code_hash = EXCLUDED.code_hash,
                       code_attempts = 0,
                       created_at = EXCLUDED.created_at,
                       expires_at = EXCLUDED.expires_at
                 WHERE who_email_address_request.created_at <= :cutoff
                """)
            .param("id", request.id())
            .param(PARAM_IDENTITY_ID, request.identityId())
            .param("address", request.address())
            .param("replaces", request.replacesCredentialId())
            .param("tokenHash", tokenHash)
            .param("codeHash", codeHash)
            .param(PARAM_NOW, utc(request.createdAt()))
            .param("expiresAt", utc(request.expiresAt()))
            .param("cutoff", utc(request.createdAt().minus(resendInterval)))
            .update()
        > 0;
  }

  @Override
  public Optional<EmailAddressRequest> consumeToken(
      UUID identityId, String tokenHash, Instant now) {
    return jdbcClient
        .sql(
            "DELETE FROM who_email_address_request"
                + " WHERE identity_id = :identityId AND token_hash = :hash AND expires_at > :now"
                + RETURNING)
        .param(PARAM_IDENTITY_ID, identityId)
        .param(PARAM_HASH, tokenHash)
        .param(PARAM_NOW, utc(now))
        .query(JdbcEmailAddressRequestRepository::request)
        .optional();
  }

  @Override
  public Optional<EmailAddressCodeAttempt> attemptCode(
      UUID identityId, int maxAttempts, Instant now) {
    return jdbcClient
        .sql(
            """
            UPDATE who_email_address_request
               SET code_attempts = code_attempts + 1
             WHERE identity_id = :identityId AND code_attempts < :maxAttempts AND expires_at > :now
            RETURNING id, code_hash
            """)
        .param(PARAM_IDENTITY_ID, identityId)
        .param("maxAttempts", maxAttempts)
        .param(PARAM_NOW, utc(now))
        .query(
            (rs, rowNum) ->
                new EmailAddressCodeAttempt(
                    rs.getObject("id", UUID.class), rs.getString("code_hash")))
        .optional();
  }

  @Override
  public Optional<EmailAddressRequest> consumeCode(UUID requestId, String codeHash, Instant now) {
    return jdbcClient
        .sql(
            "DELETE FROM who_email_address_request"
                + " WHERE id = :id AND code_hash = :hash AND expires_at > :now"
                + RETURNING)
        .param("id", requestId)
        .param(PARAM_HASH, codeHash)
        .param(PARAM_NOW, utc(now))
        .query(JdbcEmailAddressRequestRepository::request)
        .optional();
  }

  @Override
  public void delete(UUID requestId) {
    jdbcClient
        .sql("DELETE FROM who_email_address_request WHERE id = :id")
        .param("id", requestId)
        .update();
  }

  private static EmailAddressRequest request(ResultSet rs, int rowNum) throws SQLException {
    return new EmailAddressRequest(
        rs.getObject("id", UUID.class),
        rs.getObject("identity_id", UUID.class),
        rs.getString("address"),
        rs.getObject("replaces_credential_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
