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
package org.jwcarman.who.jdbc.repository;

import org.jwcarman.who.core.domain.ExternalIdentity;
import org.jwcarman.who.core.repository.ExternalIdentityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcExternalIdentityRepository implements ExternalIdentityRepository {

    // Column name constants
    private static final String COL_ID = "id";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_ISSUER = "issuer";
    private static final String COL_SUBJECT = "subject";

    // Parameter name constants
    private static final String PARAM_ID = "id";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_ISSUER = "issuer";
    private static final String PARAM_SUBJECT = "subject";

    private final JdbcClient jdbcClient;

    public JdbcExternalIdentityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ExternalIdentity> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, user_id, issuer, subject
                FROM who_external_identity
                WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query((rs, rowNum) -> new ExternalIdentity(
                UUID.fromString(rs.getString(COL_ID)),
                UUID.fromString(rs.getString(COL_USER_ID)),
                rs.getString(COL_ISSUER),
                rs.getString(COL_SUBJECT)
            ))
            .optional();
    }

    @Override
    public Optional<ExternalIdentity> findByIssuerAndSubject(String issuer, String subject) {
        return jdbcClient.sql("""
                SELECT id, user_id, issuer, subject
                FROM who_external_identity
                WHERE issuer = :issuer AND subject = :subject
                """)
            .param(PARAM_ISSUER, issuer)
            .param(PARAM_SUBJECT, subject)
            .query((rs, rowNum) -> new ExternalIdentity(
                UUID.fromString(rs.getString(COL_ID)),
                UUID.fromString(rs.getString(COL_USER_ID)),
                rs.getString(COL_ISSUER),
                rs.getString(COL_SUBJECT)
            ))
            .optional();
    }

    @Override
    public List<ExternalIdentity> findByUserId(UUID userId) {
        return jdbcClient.sql("""
                SELECT id, user_id, issuer, subject
                FROM who_external_identity
                WHERE user_id = :userId
                """)
            .param(PARAM_USER_ID, userId)
            .query((rs, rowNum) -> new ExternalIdentity(
                UUID.fromString(rs.getString(COL_ID)),
                UUID.fromString(rs.getString(COL_USER_ID)),
                rs.getString(COL_ISSUER),
                rs.getString(COL_SUBJECT)
            ))
            .list();
    }

    @Override
    public long countByUserId(UUID userId) {
        Long count = jdbcClient.sql("""
                SELECT COUNT(*) FROM who_external_identity WHERE user_id = :userId
                """)
            .param(PARAM_USER_ID, userId)
            .query(Long.class)
            .single();
        return count != null ? count : 0L;
    }

    @Override
    public ExternalIdentity save(ExternalIdentity identity) {
        int updated = jdbcClient.sql("""
                UPDATE who_external_identity
                SET user_id = :userId, issuer = :issuer, subject = :subject
                WHERE id = :id
                """)
            .param(PARAM_ID, identity.id())
            .param(PARAM_USER_ID, identity.userId())
            .param(PARAM_ISSUER, identity.issuer())
            .param(PARAM_SUBJECT, identity.subject())
            .update();

        if (updated == 0) {
            // Insert new identity
            jdbcClient.sql("""
                    INSERT INTO who_external_identity (id, user_id, issuer, subject)
                    VALUES (:id, :userId, :issuer, :subject)
                    """)
                .param(PARAM_ID, identity.id())
                .param(PARAM_USER_ID, identity.userId())
                .param(PARAM_ISSUER, identity.issuer())
                .param(PARAM_SUBJECT, identity.subject())
                .update();
        }

        return identity;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_external_identity WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .update();
    }
}
