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

@Repository
public class JdbcRolePermissionRepository implements RolePermissionRepository {

    private final JdbcClient jdbcClient;

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
            .param("roleId", roleId)
            .param("permissionId", permissionId)
            .update();
    }

    @Override
    public void removePermission(UUID roleId, String permissionId) {
        jdbcClient.sql("""
                DELETE FROM who_role_permission
                WHERE role_id = :roleId AND permission_id = :permissionId
                """)
            .param("roleId", roleId)
            .param("permissionId", permissionId)
            .update();
    }

    @Override
    public void removeAllPermissionsForRole(UUID roleId) {
        jdbcClient.sql("""
                DELETE FROM who_role_permission WHERE role_id = :roleId
                """)
            .param("roleId", roleId)
            .update();
    }

    @Override
    public List<String> findPermissionIdsByRoleId(UUID roleId) {
        return jdbcClient.sql("""
                SELECT permission_id
                FROM who_role_permission
                WHERE role_id = :roleId
                """)
            .param("roleId", roleId)
            .query((rs, rowNum) -> rs.getString("permission_id"))
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
            .param("roleIds", roleIds)
            .query((rs, rowNum) -> rs.getString("permission_id"))
            .list();
    }
}
