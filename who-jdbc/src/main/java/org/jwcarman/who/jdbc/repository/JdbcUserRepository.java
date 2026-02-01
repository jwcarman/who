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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcClient jdbcClient;

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
            .param("id", id)
            .query((rs, rowNum) -> new User(
                UUID.fromString(rs.getString("id")),
                UserStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
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
            .param("id", user.id())
            .param("status", user.status().name())
            .param("updatedAt", Timestamp.from(user.updatedAt()))
            .update();

        if (updated == 0) {
            // Insert new user
            jdbcClient.sql("""
                    INSERT INTO who_user (id, status, created_at, updated_at)
                    VALUES (:id, :status, :createdAt, :updatedAt)
                    """)
                .param("id", user.id())
                .param("status", user.status().name())
                .param("createdAt", Timestamp.from(user.createdAt()))
                .param("updatedAt", Timestamp.from(user.updatedAt()))
                .update();
        }

        return user;
    }

    @Override
    public boolean existsById(UUID id) {
        Integer count = jdbcClient.sql("""
                SELECT COUNT(*) FROM who_user WHERE id = :id
                """)
            .param("id", id)
            .query(Integer.class)
            .single();
        return count > 0;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_user WHERE id = :id
                """)
            .param("id", id)
            .update();
    }
}
