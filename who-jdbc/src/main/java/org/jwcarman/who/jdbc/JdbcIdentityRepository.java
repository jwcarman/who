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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** JDBC implementation of {@link IdentityRepository} backed by the {@code who_identity} table. */
public class JdbcIdentityRepository implements IdentityRepository {

  private static final String COL_ID = "id";
  private static final String COL_STATUS = "status";
  private static final String COL_CREATED_AT = "created_at";
  private static final String COL_UPDATED_AT = "updated_at";

  private static final String PAGE =
      """
      SELECT id, status, created_at, updated_at FROM who_identity
      WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
        AND (:anyIds = FALSE OR id = ANY(CAST(:ids AS uuid[])))
      ORDER BY created_at, id LIMIT :size OFFSET :offset
      """;

  // a join in the query, so a role with many holders is never a long list of ids
  private static final String PAGE_BY_ROLE =
      """
      SELECT id, status, created_at, updated_at FROM who_identity
      WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
        AND EXISTS (SELECT 1 FROM who_identity_role ir
                    WHERE ir.identity_id = who_identity.id AND ir.role_id = :roleId)
        AND (:anyIds = FALSE OR id = ANY(CAST(:ids AS uuid[])))
      ORDER BY created_at, id LIMIT :size OFFSET :offset
      """;

  private static final String COUNT =
      """
      SELECT COUNT(*) FROM who_identity
      WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
        AND (:anyIds = FALSE OR id = ANY(CAST(:ids AS uuid[])))
      """;

  private static final String COUNT_BY_ROLE =
      """
      SELECT COUNT(*) FROM who_identity
      WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
        AND EXISTS (SELECT 1 FROM who_identity_role ir
                    WHERE ir.identity_id = who_identity.id AND ir.role_id = :roleId)
        AND (:anyIds = FALSE OR id = ANY(CAST(:ids AS uuid[])))
      """;

  private static final RowMapper<Identity> IDENTITY_ROW_MAPPER =
      (rs, rowNum) ->
          new Identity(
              rs.getObject(COL_ID, UUID.class),
              IdentityStatus.valueOf(rs.getString(COL_STATUS)),
              rs.getObject(COL_CREATED_AT, OffsetDateTime.class).toInstant(),
              rs.getObject(COL_UPDATED_AT, OffsetDateTime.class).toInstant());

  private final JdbcClient jdbcClient;

  /**
   * Creates the repository, which keeps identities in who's tables.
   *
   * @param jdbcClient the database
   */
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
  public Optional<Identity> lockById(UUID id) {
    return jdbcClient
        .sql(
            "SELECT id, status, created_at, updated_at FROM who_identity WHERE id = :id FOR UPDATE")
        .param(COL_ID, id)
        .query(IDENTITY_ROW_MAPPER)
        .optional();
  }

  @Override
  public List<Identity> findPage(
      Optional<IdentityStatus> status,
      Optional<UUID> roleId,
      Optional<Set<UUID>> ids,
      int page,
      int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page must not be negative, got " + page);
    }
    if (size < 1) {
      throw new IllegalArgumentException("size must be positive, got " + size);
    }
    if (ids.filter(Set::isEmpty).isPresent()) {
      return List.of();
    }
    return withFilters(
            jdbcClient.sql(roleId.isPresent() ? PAGE_BY_ROLE : PAGE), status, roleId, ids)
        .param("size", size)
        .param("offset", (long) page * size)
        .query(IDENTITY_ROW_MAPPER)
        .list();
  }

  @Override
  public long count(
      Optional<IdentityStatus> status, Optional<UUID> roleId, Optional<Set<UUID>> ids) {
    if (ids.filter(Set::isEmpty).isPresent()) {
      return 0;
    }
    return withFilters(
            jdbcClient.sql(roleId.isPresent() ? COUNT_BY_ROLE : COUNT), status, roleId, ids)
        .query(Long.class)
        .single();
  }

  /**
   * Binds the filters; an absent one is a typed null (or an empty array), which its predicate
   * ignores. The query text never changes with them, apart from joining the role table only when a
   * role is asked for, since that table belongs to who-rbac.
   */
  private static JdbcClient.StatementSpec withFilters(
      JdbcClient.StatementSpec statement,
      Optional<IdentityStatus> status,
      Optional<UUID> roleId,
      Optional<Set<UUID>> ids) {
    JdbcClient.StatementSpec bound =
        statement.param(COL_STATUS, status.map(IdentityStatus::name).orElse(null));
    if (roleId.isPresent()) {
      bound = bound.param("roleId", roleId.get());
    }
    return bound
        .param("anyIds", ids.isPresent())
        .param("ids", ids.map(set -> set.toArray(UUID[]::new)).orElseGet(() -> new UUID[0]));
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
        .param("createdAt", identity.createdAt().atOffset(ZoneOffset.UTC))
        .param("updatedAt", identity.updatedAt().atOffset(ZoneOffset.UTC))
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

  @Override
  public boolean changeStatus(UUID id, IdentityStatus status) {
    return jdbcClient
            .sql(
                "UPDATE who_identity SET status = :status, updated_at = :updatedAt WHERE id = :id AND status <> :status")
            .param(COL_ID, id)
            .param(COL_STATUS, status.name())
            .param("updatedAt", Instant.now().atOffset(ZoneOffset.UTC))
            .update()
        > 0;
  }
}
