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

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link EnrollmentTokenRepository} backed by the
 * {@code who_enrollment_token} table.
 */
@Repository
public class JdbcEnrollmentTokenRepository implements EnrollmentTokenRepository {

    private static final RowMapper<EnrollmentToken> ROW_MAPPER = (rs, rowNum) -> new EnrollmentToken(
            rs.getObject("id", UUID.class),
            rs.getObject("identity_id", UUID.class),
            rs.getString("value"),
            EnrollmentTokenStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant()
    );

    private final JdbcClient jdbcClient;

    public JdbcEnrollmentTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public EnrollmentToken save(EnrollmentToken token) {
        jdbcClient
                .sql("""
                        INSERT INTO who_enrollment_token (id, identity_id, value, status, created_at, expires_at)
                        VALUES (:id, :identityId, :value, :status, :createdAt, :expiresAt)
                        ON CONFLICT (id) DO UPDATE SET status = :status
                        """)
                .param("id", token.id())
                .param("identityId", token.identityId())
                .param("value", token.value())
                .param("status", token.status().name())
                .param("createdAt", Timestamp.from(token.createdAt()))
                .param("expiresAt", Timestamp.from(token.expiresAt()))
                .update();
        return token;
    }

    @Override
    public Optional<EnrollmentToken> findById(UUID id) {
        return jdbcClient
                .sql("SELECT id, identity_id, value, status, created_at, expires_at FROM who_enrollment_token WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<EnrollmentToken> findByValue(String value) {
        return jdbcClient
                .sql("SELECT id, identity_id, value, status, created_at, expires_at FROM who_enrollment_token WHERE value = :value")
                .param("value", value)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient
                .sql("DELETE FROM who_enrollment_token WHERE id = :id")
                .param("id", id)
                .update();
    }
}
