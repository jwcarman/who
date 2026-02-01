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

import org.jwcarman.who.core.domain.UserPreferences;
import org.jwcarman.who.core.repository.UserPreferencesRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserPreferencesRepository implements UserPreferencesRepository {

    private final JdbcClient jdbcClient;

    public JdbcUserPreferencesRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<UserPreferences> findByUserIdAndNamespace(UUID userId, String namespace) {
        return jdbcClient.sql("""
                SELECT id, user_id, namespace, data
                FROM who_user_preference
                WHERE user_id = :userId AND namespace = :namespace
                """)
            .param("userId", userId)
            .param("namespace", namespace)
            .query((rs, rowNum) -> new UserPreferences(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("namespace"),
                rs.getString("data")
            ))
            .optional();
    }

    @Override
    public UserPreferences save(UserPreferences preferences) {
        int updated = jdbcClient.sql("""
                UPDATE who_user_preference
                SET data = :data
                WHERE id = :id
                """)
            .param("id", preferences.id())
            .param("data", preferences.data())
            .update();

        if (updated == 0) {
            // Insert new preferences
            jdbcClient.sql("""
                    INSERT INTO who_user_preference (id, user_id, namespace, data)
                    VALUES (:id, :userId, :namespace, :data)
                    """)
                .param("id", preferences.id())
                .param("userId", preferences.userId())
                .param("namespace", preferences.namespace())
                .param("data", preferences.data())
                .update();
        }

        return preferences;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_user_preference WHERE id = :id
                """)
            .param("id", id)
            .update();
    }
}
