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
package org.jwcarman.who.jwt;

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC implementation of {@link JwtCredentialRepository} backed by the {@code who_jwt_credential}
 * table. It is also the JWT module's {@link CredentialRemover}.
 */
public class JdbcJwtCredentialRepository implements JwtCredentialRepository, CredentialRemover {

  private static final String COL_ID = "id";
  private static final String COL_ISSUER = "issuer";
  private static final String COL_SUBJECT = "subject";

  private static final RowMapper<JwtCredential> ROW_MAPPER =
      (rs, rowNum) ->
          new JwtCredential(
              rs.getObject(COL_ID, UUID.class),
              rs.getString(COL_ISSUER),
              rs.getString(COL_SUBJECT));

  private final JdbcClient jdbcClient;

  public JdbcJwtCredentialRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<JwtCredential> findByIssuerAndSubject(String issuer, String subject) {
    return jdbcClient
        .sql(
            "SELECT id, issuer, subject FROM who_jwt_credential WHERE issuer = :issuer AND subject = :subject")
        .param(COL_ISSUER, issuer)
        .param(COL_SUBJECT, subject)
        .query(ROW_MAPPER)
        .optional();
  }

  /** Finds the JWT credential with the given id. */
  @Override
  public Optional<JwtCredential> findById(UUID id) {
    return jdbcClient
        .sql("SELECT id, issuer, subject FROM who_jwt_credential WHERE id = :id")
        .param(COL_ID, id)
        .query(ROW_MAPPER)
        .optional();
  }

  /** Finds the JWT credential with the given id, labelled with its issuer. */
  @Override
  public Optional<Credential> find(UUID credentialId) {
    return findById(credentialId).map(Credential.class::cast);
  }

  @Override
  public JwtCredential save(JwtCredential credential) {
    jdbcClient
        .sql(
            """
                        INSERT INTO who_jwt_credential (id, issuer, subject)
                        VALUES (:id, :issuer, :subject)
                        ON CONFLICT (id) DO NOTHING
                        """)
        .param(COL_ID, credential.id())
        .param(COL_ISSUER, credential.issuer())
        .param(COL_SUBJECT, credential.subject())
        .update();
    return credential;
  }

  @Override
  public void deleteById(UUID id) {
    jdbcClient.sql("DELETE FROM who_jwt_credential WHERE id = :id").param(COL_ID, id).update();
  }

  @Override
  public boolean remove(UUID credentialId) {
    return jdbcClient
            .sql("DELETE FROM who_jwt_credential WHERE id = :id")
            .param(COL_ID, credentialId)
            .update()
        > 0;
  }

  @Override
  public int removeUnlinked() {
    return jdbcClient
        .sql(
            "DELETE FROM who_jwt_credential j WHERE NOT EXISTS"
                + " (SELECT 1 FROM who_credential_identity ci WHERE ci.credential_id = j.id)")
        .update();
  }
}
