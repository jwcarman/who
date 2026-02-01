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

import org.jwcarman.who.core.domain.Permission;
import org.jwcarman.who.core.repository.PermissionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPermissionRepository implements PermissionRepository {

    // Column name constants
    private static final String COL_ID = "id";
    private static final String COL_DESCRIPTION = "description";

    // Parameter name constants
    private static final String PARAM_ID = "id";
    private static final String PARAM_DESCRIPTION = "description";

    private final JdbcClient jdbcClient;

    public JdbcPermissionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Permission> findById(String id) {
        return jdbcClient.sql("""
                SELECT id, description
                FROM who_permission
                WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query((rs, rowNum) -> new Permission(
                rs.getString(COL_ID),
                rs.getString(COL_DESCRIPTION)
            ))
            .optional();
    }

    @Override
    public List<Permission> findAll() {
        return jdbcClient.sql("""
                SELECT id, description
                FROM who_permission
                ORDER BY id
                """)
            .query((rs, rowNum) -> new Permission(
                rs.getString(COL_ID),
                rs.getString(COL_DESCRIPTION)
            ))
            .list();
    }

    @Override
    public Permission save(Permission permission) {
        int updated = jdbcClient.sql("""
                UPDATE who_permission
                SET description = :description
                WHERE id = :id
                """)
            .param(PARAM_ID, permission.id())
            .param(PARAM_DESCRIPTION, permission.description())
            .update();

        if (updated == 0) {
            // Insert new permission
            jdbcClient.sql("""
                    INSERT INTO who_permission (id, description)
                    VALUES (:id, :description)
                    """)
                .param(PARAM_ID, permission.id())
                .param(PARAM_DESCRIPTION, permission.description())
                .update();
        }

        return permission;
    }

    @Override
    public boolean existsById(String id) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*) FROM who_permission WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .query(Integer.class)
            .single();
        return count > 0;
    }

    @Override
    public void deleteById(String id) {
        jdbcClient.sql("""
                DELETE FROM who_permission WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .update();
    }
}
