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

/**
 * JDBC implementation of {@link UserPreferencesRepository}.
 * <p>
 * Uses Spring's {@link JdbcClient} for database access. Maps to the {@code who_user_preferences} table.
 */
@Repository
public class JdbcUserPreferencesRepository implements UserPreferencesRepository {

    private static final String COL_ID = "id";
    private static final String COL_USER_ID = "user_id";
    private static final String COL_NAMESPACE = "namespace";
    private static final String COL_DATA = "data";

    private static final String PARAM_ID = "id";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_NAMESPACE = "namespace";
    private static final String PARAM_DATA = "data";

    private final JdbcClient jdbcClient;

    /**
     * Constructs a new JdbcUserPreferencesRepository with the provided JDBC client.
     *
     * @param jdbcClient the JDBC client for database access
     */
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
            .param(PARAM_USER_ID, userId)
            .param(PARAM_NAMESPACE, namespace)
            .query((rs, rowNum) -> new UserPreferences(
                UUID.fromString(rs.getString(COL_ID)),
                UUID.fromString(rs.getString(COL_USER_ID)),
                rs.getString(COL_NAMESPACE),
                rs.getString(COL_DATA)
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
            .param(PARAM_ID, preferences.id())
            .param(PARAM_DATA, preferences.data())
            .update();

        if (updated == 0) {
            // Insert new preferences
            jdbcClient.sql("""
                    INSERT INTO who_user_preference (id, user_id, namespace, data)
                    VALUES (:id, :userId, :namespace, :data)
                    """)
                .param(PARAM_ID, preferences.id())
                .param(PARAM_USER_ID, preferences.userId())
                .param(PARAM_NAMESPACE, preferences.namespace())
                .param(PARAM_DATA, preferences.data())
                .update();
        }

        return preferences;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_user_preference WHERE id = :id
                """)
            .param(PARAM_ID, id)
            .update();
    }
}
