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

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;
import org.jwcarman.who.core.repository.ContactMethodRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link ContactMethodRepository}.
 * <p>
 * Uses Spring's {@link JdbcClient} for database access. Maps to the {@code who_contact_method} table.
 */
@Repository
public class JdbcContactMethodRepository implements ContactMethodRepository {

    // Column names
    private static final String COL_ID = "id";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_TYPE = "type";
    private static final String COL_VALUE = "value";
    private static final String COL_VERIFIED = "verified";
    private static final String COL_VERIFIED_AT = "verified_at";
    private static final String COL_CREATED_AT = "created_at";

    // Parameter names
    private static final String PARAM_ID = "id";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_TYPE = "type";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_VERIFIED = "verified";
    private static final String PARAM_VERIFIED_AT = "verifiedAt";
    private static final String PARAM_CREATED_AT = "createdAt";

    private final JdbcClient jdbcClient;

    /**
     * Constructs a new JdbcContactMethodRepository with the provided JDBC client.
     *
     * @param jdbcClient the JDBC client for database access
     */
    public JdbcContactMethodRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ContactMethod save(ContactMethod contactMethod) {
        int updated = jdbcClient.sql("""
                UPDATE who_contact_method
                SET user_id = :userId,
                    type = :type,
                    "value" = :value,
                    verified = :verified,
                    verified_at = :verifiedAt,
                    created_at = :createdAt
                WHERE id = :id
                """)
            .param(PARAM_ID, contactMethod.id())
            .param(PARAM_USER_ID, contactMethod.userId())
            .param(PARAM_TYPE, contactMethod.type().name())
            .param(PARAM_VALUE, contactMethod.value())
            .param(PARAM_VERIFIED, contactMethod.verified())
            .param(PARAM_VERIFIED_AT, contactMethod.verifiedAt() != null ? Timestamp.from(contactMethod.verifiedAt()) : null)
            .param(PARAM_CREATED_AT, Timestamp.from(contactMethod.createdAt()))
            .update();

        if (updated == 0) {
            // Insert new contact method
            jdbcClient.sql("""
                    INSERT INTO who_contact_method (id, user_id, type, "value", verified, verified_at, created_at)
                    VALUES (:id, :userId, :type, :value, :verified, :verifiedAt, :createdAt)
                    """)
                .param(PARAM_ID, contactMethod.id())
                .param(PARAM_USER_ID, contactMethod.userId())
                .param(PARAM_TYPE, contactMethod.type().name())
                .param(PARAM_VALUE, contactMethod.value())
                .param(PARAM_VERIFIED, contactMethod.verified())
                .param(PARAM_VERIFIED_AT, contactMethod.verifiedAt() != null ? Timestamp.from(contactMethod.verifiedAt()) : null)
                .param(PARAM_CREATED_AT, Timestamp.from(contactMethod.createdAt()))
                .update();
        }

        return contactMethod;
    }

    @Override
    public Optional<ContactMethod> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, user_id, type, "value", verified, verified_at, created_at
                FROM who_contact_method
                WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query(this::mapRow)
            .optional();
    }

    @Override
    public List<ContactMethod> findByUserId(UUID userId) {
        return jdbcClient.sql("""
                SELECT id, user_id, type, "value", verified, verified_at, created_at
                FROM who_contact_method
                WHERE user_id = :userId
                """)
            .param(PARAM_USER_ID, userId)
            .query(this::mapRow)
            .list();
    }

    @Override
    public Optional<ContactMethod> findByUserIdAndType(UUID userId, ContactType type) {
        return jdbcClient.sql("""
                SELECT id, user_id, type, "value", verified, verified_at, created_at
                FROM who_contact_method
                WHERE user_id = :userId AND type = :type
                """)
            .param(PARAM_USER_ID, userId)
            .param(PARAM_TYPE, type.name())
            .query(this::mapRow)
            .optional();
    }

    @Override
    public Optional<ContactMethod> findByTypeAndValue(ContactType type, String value) {
        return jdbcClient.sql("""
                SELECT id, user_id, type, "value", verified, verified_at, created_at
                FROM who_contact_method
                WHERE type = :type AND "value" = :value
                """)
            .param(PARAM_TYPE, type.name())
            .param(PARAM_VALUE, value)
            .query(this::mapRow)
            .optional();
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_contact_method WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .update();
    }

    private ContactMethod mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp verifiedAtTimestamp = rs.getTimestamp(COL_VERIFIED_AT);
        return new ContactMethod(
            UUID.fromString(rs.getString(COL_ID)),
            UUID.fromString(rs.getString(COL_USER_ID)),
            ContactType.valueOf(rs.getString(COL_TYPE)),
            rs.getString(COL_VALUE),
            rs.getBoolean(COL_VERIFIED),
            verifiedAtTimestamp != null ? verifiedAtTimestamp.toInstant() : null,
            rs.getTimestamp(COL_CREATED_AT).toInstant()
        );
    }
}
