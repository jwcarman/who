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
package org.jwcarman.who.apikey;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC implementation of {@link ApiKeyCredentialRepository} backed by the {@code
 * who_api_key_credential} table. It is also the API key module's {@link CredentialRemover}.
 */
public class JdbcApiKeyCredentialRepository
    implements ApiKeyCredentialRepository, CredentialRemover {

  private static final String COL_ID = "id";
  private static final String COL_NAME = "name";
  private static final String COL_KEY_HASH = "key_hash";
  private static final String PARAM_EXPIRES_AT = "expiresAt";
  private static final String PARAM_KEY_HASH = "keyHash";
  private static final String COLUMNS = "id, name, key_hash, created_at, expires_at, last_used_at";

  /** A key's row, before its scopes are read. */
  private record Row(
      UUID id, String name, String keyHash, Instant createdAt, Instant expiresAt, Instant used) {}

  private static final RowMapper<Row> ROW_MAPPER =
      (rs, rowNum) ->
          new Row(
              rs.getObject(COL_ID, UUID.class),
              rs.getString(COL_NAME),
              rs.getString(COL_KEY_HASH),
              instant(rs, "created_at"),
              instant(rs, "expires_at"),
              instant(rs, "last_used_at"));

  private final JdbcClient jdbcClient;

  /**
   * Creates a new {@code JdbcApiKeyCredentialRepository}.
   *
   * @param jdbcClient the JDBC client used to execute queries
   */
  public JdbcApiKeyCredentialRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<ApiKeyCredential> findByKeyHash(String keyHash) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM who_api_key_credential WHERE key_hash = :keyHash")
        .param(PARAM_KEY_HASH, keyHash)
        .query(ROW_MAPPER)
        .optional()
        .map(this::withScopes);
  }

  @Override
  public Optional<ApiKeyCredential> findById(UUID id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM who_api_key_credential WHERE id = :id")
        .param(COL_ID, id)
        .query(ROW_MAPPER)
        .optional()
        .map(this::withScopes);
  }

  @Override
  public ApiKeyCredential save(ApiKeyCredential credential) {
    jdbcClient
        .sql(
            """
                        INSERT INTO who_api_key_credential
                            (id, name, key_hash, created_at, expires_at, last_used_at)
                        VALUES (:id, :name, :keyHash, :createdAt, :expiresAt, :lastUsedAt)
                        ON CONFLICT (id) DO UPDATE SET name = :name, key_hash = :keyHash,
                            created_at = :createdAt, expires_at = :expiresAt,
                            last_used_at = :lastUsedAt
                        """)
        .param(COL_ID, credential.id())
        .param(COL_NAME, credential.name())
        .param(PARAM_KEY_HASH, credential.keyHash())
        .param("createdAt", offset(credential.createdAt()))
        .param(PARAM_EXPIRES_AT, offset(credential.expiresAt()))
        .param("lastUsedAt", offset(credential.lastUsedAt()))
        .update();
    jdbcClient
        .sql("DELETE FROM who_api_key_scope WHERE credential_id = :id")
        .param(COL_ID, credential.id())
        .update();
    for (String scope : credential.scopes()) {
      jdbcClient
          .sql(
              "INSERT INTO who_api_key_scope (credential_id, permission) VALUES (:id, :permission)")
          .param(COL_ID, credential.id())
          .param("permission", scope)
          .update();
    }
    return credential;
  }

  @Override
  public boolean touch(UUID id, Instant now, Instant notSince) {
    return jdbcClient
            .sql(
                """
                UPDATE who_api_key_credential SET last_used_at = :now
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :notSince)
                """)
            .param(COL_ID, id)
            .param("now", offset(now))
            .param("notSince", offset(notSince))
            .update()
        > 0;
  }

  @Override
  public boolean expireBy(UUID id, Instant expiresAt, Instant now) {
    return jdbcClient
            .sql(
                """
                UPDATE who_api_key_credential
                SET expires_at = CASE WHEN expires_at < :expiresAt THEN expires_at
                                      ELSE :expiresAt END
                WHERE id = :id AND expires_at > :now
                """)
            .param(COL_ID, id)
            .param(PARAM_EXPIRES_AT, offset(expiresAt))
            .param("now", offset(now))
            .update()
        > 0;
  }

  private ApiKeyCredential withScopes(Row row) {
    Set<String> scopes =
        new HashSet<>(
            jdbcClient
                .sql("SELECT permission FROM who_api_key_scope WHERE credential_id = :id")
                .param(COL_ID, row.id())
                .query(String.class)
                .list());
    return new ApiKeyCredential(
        row.id(), row.name(), row.keyHash(), scopes, row.createdAt(), row.expiresAt(), row.used());
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime offset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  @Override
  public boolean deleteById(UUID id) {
    return jdbcClient
            .sql("DELETE FROM who_api_key_credential WHERE id = :id")
            .param(COL_ID, id)
            .update()
        > 0;
  }

  @Override
  public boolean remove(UUID credentialId) {
    return deleteById(credentialId);
  }

  /** Finds the API key with the given id, an {@link ApiKeyCredential} labelled with its name. */
  @Override
  public Optional<Credential> find(UUID credentialId) {
    return findById(credentialId).map(Credential.class::cast);
  }

  @Override
  public int removeUnlinked() {
    return jdbcClient
        .sql(
            "DELETE FROM who_api_key_credential k WHERE NOT EXISTS"
                + " (SELECT 1 FROM who_credential_identity ci WHERE ci.credential_id = k.id)")
        .update();
  }
}
