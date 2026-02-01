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

import org.jwcarman.who.core.domain.Role;
import org.jwcarman.who.core.repository.RoleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRoleRepository implements RoleRepository {

    private static final String COL_ID = "id";
    private static final String COL_NAME = "name";

    private static final String PARAM_ID = "id";
    private static final String PARAM_NAME = "name";

    private final JdbcClient jdbcClient;

    public JdbcRoleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Role> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, name
                FROM who_role
                WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query((rs, rowNum) -> new Role(
                UUID.fromString(rs.getString(COL_ID)),
                rs.getString(COL_NAME)
            ))
            .optional();
    }

    @Override
    public Optional<Role> findByName(String name) {
        return jdbcClient.sql("""
                SELECT id, name
                FROM who_role
                WHERE name = :name
                """)
            .param(PARAM_NAME, name)
            .query((rs, rowNum) -> new Role(
                UUID.fromString(rs.getString(COL_ID)),
                rs.getString(COL_NAME)
            ))
            .optional();
    }

    @Override
    public Role save(Role role) {
        int updated = jdbcClient.sql("""
                UPDATE who_role
                SET name = :name
                WHERE id = :id
                """)
            .param(PARAM_ID, role.id())
            .param(PARAM_NAME, role.name())
            .update();

        if (updated == 0) {
            // Insert new role
            jdbcClient.sql("""
                    INSERT INTO who_role (id, name)
                    VALUES (:id, :name)
                    """)
                .param(PARAM_ID, role.id())
                .param(PARAM_NAME, role.name())
                .update();
        }

        return role;
    }

    @Override
    public boolean existsById(UUID id) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*) FROM who_role WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query(Integer.class)
            .single();
        return count > 0;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_role WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .update();
    }
}
