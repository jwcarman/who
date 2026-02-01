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

import org.jwcarman.who.core.repository.UserRoleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link UserRoleRepository}.
 * <p>
 * Uses Spring's {@link JdbcClient} for database access. Maps to the {@code who_user_role} table
 * which represents the many-to-many relationship between users and roles.
 */
@Repository
public class JdbcUserRoleRepository implements UserRoleRepository {

    // Column names
    private static final String COL_ROLE_ID = "role_id";

    // Parameter names
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_ROLE_ID = "roleId";

    private final JdbcClient jdbcClient;

    /**
     * Constructs a new JdbcUserRoleRepository with the provided JDBC client.
     *
     * @param jdbcClient the JDBC client for database access
     */
    public JdbcUserRoleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<UUID> findRoleIdsByUserId(UUID userId) {
        return jdbcClient.sql("""
                SELECT role_id
                FROM who_user_role
                WHERE user_id = :userId
                """)
            .param(PARAM_USER_ID, userId)
            .query((rs, rowNum) -> UUID.fromString(rs.getString(COL_ROLE_ID)))
            .list();
    }

    @Override
    public void assignRole(UUID userId, UUID roleId) {
        jdbcClient.sql("""
                INSERT INTO who_user_role (user_id, role_id)
                VALUES (:userId, :roleId)
                ON CONFLICT DO NOTHING
                """)
            .param(PARAM_USER_ID, userId)
            .param(PARAM_ROLE_ID, roleId)
            .update();
    }

    @Override
    public void removeRole(UUID userId, UUID roleId) {
        jdbcClient.sql("""
                DELETE FROM who_user_role
                WHERE user_id = :userId AND role_id = :roleId
                """)
            .param(PARAM_USER_ID, userId)
            .param(PARAM_ROLE_ID, roleId)
            .update();
    }

    @Override
    public void removeAllAssignmentsForRole(UUID roleId) {
        jdbcClient.sql("""
                DELETE FROM who_user_role WHERE role_id = :roleId
                """)
            .param(PARAM_ROLE_ID, roleId)
            .update();
    }

    @Override
    public void removeAllAssignmentsForUser(UUID userId) {
        jdbcClient.sql("""
                DELETE FROM who_user_role WHERE user_id = :userId
                """)
            .param(PARAM_USER_ID, userId)
            .update();
    }
}
