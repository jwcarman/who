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
import java.util.UUID;

import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.repository.SessionRecordRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC implementation of {@link SessionRecordRepository} backed by the {@code who_session} table.
 */
public class JdbcSessionRecordRepository implements SessionRecordRepository {

  private static final String COLUMNS =
      "id, identity_id, credential_id, sign_in_method, signed_in_at, last_seen_at, user_agent,"
          + " ended_at";
  private static final String P_ID = "id";
  private static final String P_NOW = "now";

  private static final RowMapper<SessionRecord> ROW_MAPPER =
      (rs, rowNum) -> {
        OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
        return new SessionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("identity_id", UUID.class),
            rs.getObject("credential_id", UUID.class),
            SignInMethod.valueOf(rs.getString("sign_in_method")),
            rs.getObject("signed_in_at", OffsetDateTime.class).toInstant(),
            rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
            rs.getString("user_agent"),
            endedAt == null ? null : endedAt.toInstant());
      };

  private final JdbcClient jdbcClient;

  /**
   * Creates the repository, which keeps sessions in who's tables.
   *
   * @param jdbcClient the database
   */
  public JdbcSessionRecordRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  @Override
  public void insert(SessionRecord session) {
    jdbcClient
        .sql(
            """
            INSERT INTO who_session
              (id, identity_id, credential_id, sign_in_method, signed_in_at, last_seen_at,
               user_agent, ended_at)
            VALUES (:id, :identityId, :credentialId, :method, :signedInAt, :lastSeenAt,
                    :userAgent, :endedAt)
            """)
        .param(P_ID, session.id())
        .param("identityId", session.identityId())
        .param("credentialId", session.credentialId())
        .param("method", session.signInMethod().name())
        .param("signedInAt", utc(session.signedInAt()))
        .param("lastSeenAt", utc(session.lastSeenAt()))
        .param("userAgent", session.userAgent())
        .param("endedAt", utc(session.endedAt()))
        .update();
  }

  @Override
  public Optional<SessionRecord> findById(UUID id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM who_session WHERE id = :id")
        .param(P_ID, id)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public List<SessionRecord> findLiveByIdentity(UUID identityId, Instant seenSince) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM who_session
                 WHERE identity_id = :identityId AND ended_at IS NULL
                   AND last_seen_at >= :seenSince
                 ORDER BY signed_in_at DESC
                """)
        .param("identityId", identityId)
        .param("seenSince", utc(seenSince))
        .query(ROW_MAPPER)
        .list();
  }

  @Override
  public boolean touch(UUID id, Instant now, Instant notSince) {
    return jdbcClient
            .sql(
                """
                UPDATE who_session SET last_seen_at = :now
                 WHERE id = :id AND ended_at IS NULL AND last_seen_at < :notSince
                """)
            .param(P_ID, id)
            .param(P_NOW, utc(now))
            .param("notSince", utc(notSince))
            .update()
        == 1;
  }

  @Override
  public boolean end(UUID id, Instant now) {
    return jdbcClient
            .sql("UPDATE who_session SET ended_at = :now WHERE id = :id AND ended_at IS NULL")
            .param(P_ID, id)
            .param(P_NOW, utc(now))
            .update()
        == 1;
  }

  @Override
  public int deleteDead(Instant seenBefore, int limit) {
    return jdbcClient
        .sql(
            """
            DELETE FROM who_session WHERE id IN (
              SELECT id FROM who_session
               WHERE ended_at IS NOT NULL OR last_seen_at < :seenBefore
               LIMIT :limit)
            """)
        .param("seenBefore", utc(seenBefore))
        .param("limit", limit)
        .update();
  }
}
