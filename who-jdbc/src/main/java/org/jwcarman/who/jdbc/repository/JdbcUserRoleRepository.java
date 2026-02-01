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

@Repository
public class JdbcUserRoleRepository implements UserRoleRepository {

    private final JdbcClient jdbcClient;

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
            .param("userId", userId)
            .query((rs, rowNum) -> UUID.fromString(rs.getString("role_id")))
            .list();
    }

    @Override
    public void assignRole(UUID userId, UUID roleId) {
        jdbcClient.sql("""
                INSERT INTO who_user_role (user_id, role_id)
                VALUES (:userId, :roleId)
                ON CONFLICT DO NOTHING
                """)
            .param("userId", userId)
            .param("roleId", roleId)
            .update();
    }

    @Override
    public void removeRole(UUID userId, UUID roleId) {
        jdbcClient.sql("""
                DELETE FROM who_user_role
                WHERE user_id = :userId AND role_id = :roleId
                """)
            .param("userId", userId)
            .param("roleId", roleId)
            .update();
    }

    @Override
    public void removeAllAssignmentsForRole(UUID roleId) {
        jdbcClient.sql("""
                DELETE FROM who_user_role WHERE role_id = :roleId
                """)
            .param("roleId", roleId)
            .update();
    }

    @Override
    public void removeAllAssignmentsForUser(UUID userId) {
        jdbcClient.sql("""
                DELETE FROM who_user_role WHERE user_id = :userId
                """)
            .param("userId", userId)
            .update();
    }
}
