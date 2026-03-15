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
package org.jwcarman.who.rbac;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC implementation of {@link RolePermissionRepository} backed by the {@code who_role_permission} table.
 */
@Repository
public class JdbcRolePermissionRepository implements RolePermissionRepository {

    private static final String PARAM_ROLE_ID = "roleId";
    private static final String PARAM_PERMISSION = "permission";

    private final JdbcClient jdbcClient;

    public JdbcRolePermissionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<String> findPermissionsByRoleId(UUID roleId) {
        return new HashSet<>(jdbcClient
                .sql("SELECT permission FROM who_role_permission WHERE role_id = :roleId")
                .param(PARAM_ROLE_ID, roleId)
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> findPermissionsByRoleIds(Collection<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbcClient
                .sql("SELECT DISTINCT permission FROM who_role_permission WHERE role_id = ANY(:roleIds)")
                .param("roleIds", roleIds.toArray(UUID[]::new))
                .query(String.class)
                .list());
    }

    @Override
    public void addPermission(UUID roleId, String permission) {
        jdbcClient
                .sql("INSERT INTO who_role_permission (role_id, permission) VALUES (:roleId, :permission) ON CONFLICT DO NOTHING")
                .param(PARAM_ROLE_ID, roleId)
                .param(PARAM_PERMISSION, permission)
                .update();
    }

    @Override
    public void removePermission(UUID roleId, String permission) {
        jdbcClient
                .sql("DELETE FROM who_role_permission WHERE role_id = :roleId AND permission = :permission")
                .param(PARAM_ROLE_ID, roleId)
                .param(PARAM_PERMISSION, permission)
                .update();
    }

    @Override
    public void removeAllPermissionsForRole(UUID roleId) {
        jdbcClient
                .sql("DELETE FROM who_role_permission WHERE role_id = :roleId")
                .param(PARAM_ROLE_ID, roleId)
                .update();
    }
}
