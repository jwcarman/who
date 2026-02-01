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

import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.repository.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link UserRepository}.
 * <p>
 * Uses Spring's {@link JdbcClient} for database access. Maps to the {@code who_user} table.
 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String COL_ID = "id";
    private static final String COL_STATUS = "status";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_UPDATED_AT = "updated_at";

    private static final String PARAM_ID = "id";
    private static final String PARAM_STATUS = "status";
    private static final String PARAM_CREATED_AT = "createdAt";
    private static final String PARAM_UPDATED_AT = "updatedAt";

    private final JdbcClient jdbcClient;

    /**
     * Constructs a new JdbcUserRepository with the provided JDBC client.
     *
     * @param jdbcClient the JDBC client for database access
     */
    public JdbcUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, status, created_at, updated_at
                FROM who_user
                WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query((rs, rowNum) -> new User(
                UUID.fromString(rs.getString(COL_ID)),
                UserStatus.valueOf(rs.getString(COL_STATUS)),
                rs.getTimestamp(COL_CREATED_AT).toInstant(),
                rs.getTimestamp(COL_UPDATED_AT).toInstant()
            ))
            .optional();
    }

    @Override
    public User save(User user) {
        int updated = jdbcClient.sql("""
                UPDATE who_user
                SET status = :status, updated_at = :updatedAt
                WHERE id = :id
                """)
            .param(PARAM_ID, user.id())
            .param(PARAM_STATUS, user.status().name())
            .param(PARAM_UPDATED_AT, Timestamp.from(user.updatedAt()))
            .update();

        if (updated == 0) {
            // Insert new user
            jdbcClient.sql("""
                    INSERT INTO who_user (id, status, created_at, updated_at)
                    VALUES (:id, :status, :createdAt, :updatedAt)
                    """)
                .param(PARAM_ID, user.id())
                .param(PARAM_STATUS, user.status().name())
                .param(PARAM_CREATED_AT, Timestamp.from(user.createdAt()))
                .param(PARAM_UPDATED_AT, Timestamp.from(user.updatedAt()))
                .update();
        }

        return user;
    }

    @Override
    public boolean existsById(UUID id) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*) FROM who_user WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query(Integer.class)
            .single();
        return count > 0;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_user WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .update();
    }
}
