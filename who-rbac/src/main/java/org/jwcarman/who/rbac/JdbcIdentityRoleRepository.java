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

import java.util.List;
import java.util.UUID;

/**
 * JDBC implementation of {@link IdentityRoleRepository} backed by the {@code who_identity_role} table.
 */
@Repository
public class JdbcIdentityRoleRepository implements IdentityRoleRepository {

    private final JdbcClient jdbcClient;

    public JdbcIdentityRoleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<UUID> findRoleIdsByIdentityId(UUID identityId) {
        return jdbcClient
                .sql("SELECT role_id FROM who_identity_role WHERE identity_id = :identityId")
                .param("identityId", identityId)
                .query(UUID.class)
                .list();
    }

    @Override
    public void assignRole(UUID identityId, UUID roleId) {
        jdbcClient
                .sql("INSERT INTO who_identity_role (identity_id, role_id) VALUES (:identityId, :roleId) ON CONFLICT DO NOTHING")
                .param("identityId", identityId)
                .param("roleId", roleId)
                .update();
    }

    @Override
    public void removeRole(UUID identityId, UUID roleId) {
        jdbcClient
                .sql("DELETE FROM who_identity_role WHERE identity_id = :identityId AND role_id = :roleId")
                .param("identityId", identityId)
                .param("roleId", roleId)
                .update();
    }

    @Override
    public void removeAllRolesForIdentity(UUID identityId) {
        jdbcClient
                .sql("DELETE FROM who_identity_role WHERE identity_id = :identityId")
                .param("identityId", identityId)
                .update();
    }
}
