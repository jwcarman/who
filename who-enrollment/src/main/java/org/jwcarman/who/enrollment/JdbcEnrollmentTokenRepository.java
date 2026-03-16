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
package org.jwcarman.who.enrollment;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC implementation of {@link EnrollmentTokenRepository} backed by the {@code
 * who_enrollment_token} table.
 */
@Repository
public class JdbcEnrollmentTokenRepository implements EnrollmentTokenRepository {

  private static final String COL_ID = "id";
  private static final String COL_IDENTITY_ID = "identity_id";
  private static final String COL_VALUE = "token_value";
  private static final String COL_STATUS = "status";
  private static final String COL_CREATED_AT = "created_at";
  private static final String COL_EXPIRES_AT = "expires_at";
  private static final String COL_REDEEMED_AT = "redeemed_at";
  private static final String PARAM_IDENTITY_ID = "identityId";

  private static final RowMapper<EnrollmentToken> ROW_MAPPER =
      (rs, rowNum) -> {
        var redeemedAt = rs.getTimestamp(COL_REDEEMED_AT);
        return new EnrollmentToken(
            rs.getObject(COL_ID, UUID.class),
            rs.getObject(COL_IDENTITY_ID, UUID.class),
            rs.getString(COL_VALUE),
            EnrollmentTokenStatus.valueOf(rs.getString(COL_STATUS)),
            rs.getTimestamp(COL_CREATED_AT).toInstant(),
            rs.getTimestamp(COL_EXPIRES_AT).toInstant(),
            redeemedAt != null ? redeemedAt.toInstant() : null);
      };

  private final JdbcClient jdbcClient;

  public JdbcEnrollmentTokenRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public EnrollmentToken save(EnrollmentToken token) {
    jdbcClient
        .sql(
            """
                        INSERT INTO who_enrollment_token (id, identity_id, token_value, status, created_at, expires_at, redeemed_at)
                        VALUES (:id, :identityId, :token_value, :status, :createdAt, :expiresAt, :redeemedAt)
                        ON CONFLICT (id) DO UPDATE SET status = :status, redeemed_at = :redeemedAt
                        """)
        .param(COL_ID, token.id())
        .param(PARAM_IDENTITY_ID, token.identityId())
        .param(COL_VALUE, token.value())
        .param(COL_STATUS, token.status().name())
        .param("createdAt", Timestamp.from(token.createdAt()))
        .param("expiresAt", Timestamp.from(token.expiresAt()))
        .param("redeemedAt", token.redeemedAt() != null ? Timestamp.from(token.redeemedAt()) : null)
        .update();
    return token;
  }

  @Override
  public Optional<EnrollmentToken> findById(UUID id) {
    return jdbcClient
        .sql(
            "SELECT id, identity_id, token_value, status, created_at, expires_at, redeemed_at FROM who_enrollment_token WHERE id = :id")
        .param(COL_ID, id)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public Optional<EnrollmentToken> findByValue(String value) {
    return jdbcClient
        .sql(
            "SELECT id, identity_id, token_value, status, created_at, expires_at, redeemed_at FROM who_enrollment_token WHERE token_value = :token_value")
        .param(COL_VALUE, value)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public void revokeAllPendingForIdentity(UUID identityId) {
    jdbcClient
        .sql(
            "UPDATE who_enrollment_token SET status = 'REVOKED' WHERE identity_id = :identityId AND status = 'PENDING'")
        .param(PARAM_IDENTITY_ID, identityId)
        .update();
  }

  @Override
  public void deleteById(UUID id) {
    jdbcClient.sql("DELETE FROM who_enrollment_token WHERE id = :id").param(COL_ID, id).update();
  }
}
