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

import org.jwcarman.who.core.repository.RolePermissionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link RolePermissionRepository}.
 * <p>
 * Uses Spring's {@link JdbcClient} for database access. Maps to the {@code who_role_permission} table
 * which represents the many-to-many relationship between roles and permissions.
 */
@Repository
public class JdbcRolePermissionRepository implements RolePermissionRepository {

    // Column names
    private static final String COL_PERMISSION_ID = "permission_id";

    // Parameter names
    private static final String PARAM_ROLE_ID = "roleId";
    private static final String PARAM_PERMISSION_ID = "permissionId";
    private static final String PARAM_ROLE_IDS = "roleIds";

    private final JdbcClient jdbcClient;

    /**
     * Constructs a new JdbcRolePermissionRepository with the provided JDBC client.
     *
     * @param jdbcClient the JDBC client for database access
     */
    public JdbcRolePermissionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void assignPermission(UUID roleId, String permissionId) {
        jdbcClient.sql("""
                INSERT INTO who_role_permission (role_id, permission_id)
                VALUES (:roleId, :permissionId)
                ON CONFLICT DO NOTHING
                """)
            .param(PARAM_ROLE_ID, roleId)
            .param(PARAM_PERMISSION_ID, permissionId)
            .update();
    }

    @Override
    public void removePermission(UUID roleId, String permissionId) {
        jdbcClient.sql("""
                DELETE FROM who_role_permission
                WHERE role_id = :roleId AND permission_id = :permissionId
                """)
            .param(PARAM_ROLE_ID, roleId)
            .param(PARAM_PERMISSION_ID, permissionId)
            .update();
    }

    @Override
    public void removeAllPermissionsForRole(UUID roleId) {
        jdbcClient.sql("""
                DELETE FROM who_role_permission WHERE role_id = :roleId
                """)
            .param(PARAM_ROLE_ID, roleId)
            .update();
    }

    @Override
    public List<String> findPermissionIdsByRoleId(UUID roleId) {
        return jdbcClient.sql("""
                SELECT permission_id
                FROM who_role_permission
                WHERE role_id = :roleId
                """)
            .param(PARAM_ROLE_ID, roleId)
            .query((rs, rowNum) -> rs.getString(COL_PERMISSION_ID))
            .list();
    }

    @Override
    public List<String> findPermissionIdsByRoleIds(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient.sql("""
                SELECT DISTINCT permission_id
                FROM who_role_permission
                WHERE role_id IN (:roleIds)
                """)
            .param(PARAM_ROLE_IDS, roleIds)
            .query((rs, rowNum) -> rs.getString(COL_PERMISSION_ID))
            .list();
    }
}
