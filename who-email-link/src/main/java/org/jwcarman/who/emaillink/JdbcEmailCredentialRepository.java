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

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** JDBC implementation of {@link EmailCredentialRepository} on {@code who_email_credential}. */
public class JdbcEmailCredentialRepository implements EmailCredentialRepository {

  private static final String COL_ID = "id";
  private static final String COL_ADDRESS = "address";

  private static final RowMapper<EmailCredential> ROW_MAPPER =
      (rs, rowNum) ->
          new EmailCredential(rs.getObject(COL_ID, UUID.class), rs.getString(COL_ADDRESS));

  private final JdbcClient jdbcClient;

  /**
   * Creates a new repository.
   *
   * @param jdbcClient the JDBC client used to execute queries
   */
  public JdbcEmailCredentialRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public EmailCredential save(EmailCredential credential) {
    jdbcClient
        .sql("INSERT INTO who_email_credential (id, address) VALUES (:id, :address)")
        .param(COL_ID, credential.id())
        .param(COL_ADDRESS, credential.address())
        .update();
    return credential;
  }

  @Override
  public Optional<EmailCredential> findByAddress(String normalized) {
    return jdbcClient
        .sql("SELECT id, address FROM who_email_credential WHERE address = :address")
        .param(COL_ADDRESS, normalized)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public Optional<EmailCredential> findById(UUID id) {
    return jdbcClient
        .sql("SELECT id, address FROM who_email_credential WHERE id = :id")
        .param(COL_ID, id)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public Optional<EmailCredential> findOldestByIdentityId(UUID identityId) {
    return jdbcClient
        .sql(
            "SELECT e.id, e.address FROM who_email_credential e"
                + " JOIN who_credential_identity ci ON ci.credential_id = e.id"
                + " WHERE ci.identity_id = :identity_id ORDER BY e.id LIMIT 1")
        .param("identity_id", identityId)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public boolean deleteById(UUID id) {
    return jdbcClient
            .sql("DELETE FROM who_email_credential WHERE id = :id")
            .param(COL_ID, id)
            .update()
        > 0;
  }
}
