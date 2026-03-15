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
package org.jwcarman.who.jdbc;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link IdentityRepository} backed by the {@code who_identity} table. */
@Repository
public class JdbcIdentityRepository implements IdentityRepository {

  private static final String COL_ID = "id";
  private static final String COL_STATUS = "status";
  private static final String COL_CREATED_AT = "created_at";
  private static final String COL_UPDATED_AT = "updated_at";

  private static final RowMapper<Identity> IDENTITY_ROW_MAPPER =
      (rs, rowNum) ->
          new Identity(
              rs.getObject(COL_ID, UUID.class),
              IdentityStatus.valueOf(rs.getString(COL_STATUS)),
              rs.getTimestamp(COL_CREATED_AT).toInstant(),
              rs.getTimestamp(COL_UPDATED_AT).toInstant());

  private final JdbcClient jdbcClient;

  public JdbcIdentityRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<Identity> findById(UUID id) {
    return jdbcClient
        .sql("SELECT id, status, created_at, updated_at FROM who_identity WHERE id = :id")
        .param(COL_ID, id)
        .query(IDENTITY_ROW_MAPPER)
        .optional();
  }

  @Override
  public Identity save(Identity identity) {
    jdbcClient
        .sql(
            """
                        INSERT INTO who_identity (id, status, created_at, updated_at)
                        VALUES (:id, :status, :createdAt, :updatedAt)
                        ON CONFLICT (id) DO UPDATE SET status = :status, updated_at = :updatedAt
                        """)
        .param(COL_ID, identity.id())
        .param(COL_STATUS, identity.status().name())
        .param("createdAt", Timestamp.from(identity.createdAt()))
        .param("updatedAt", Timestamp.from(identity.updatedAt()))
        .update();
    return identity;
  }

  @Override
  public boolean existsById(UUID id) {
    Integer count =
        jdbcClient
            .sql("SELECT COUNT(*) FROM who_identity WHERE id = :id")
            .param(COL_ID, id)
            .query(Integer.class)
            .single();
    return count > 0;
  }

  @Override
  public void deleteById(UUID id) {
    jdbcClient.sql("DELETE FROM who_identity WHERE id = :id").param(COL_ID, id).update();
  }
}
