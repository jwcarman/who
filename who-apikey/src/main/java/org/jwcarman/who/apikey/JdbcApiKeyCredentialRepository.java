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
package org.jwcarman.who.apikey;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link ApiKeyCredentialRepository} backed by the
 * {@code who_api_key_credential} table.
 */
@Repository
public class JdbcApiKeyCredentialRepository implements ApiKeyCredentialRepository {

    private static final RowMapper<ApiKeyCredential> ROW_MAPPER = (rs, rowNum) ->
            new ApiKeyCredential(
                    rs.getObject("id", UUID.class),
                    rs.getString("key_hash"));

    private final JdbcClient jdbcClient;

    public JdbcApiKeyCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ApiKeyCredential> findByKeyHash(String keyHash) {
        return jdbcClient
                .sql("SELECT id, key_hash FROM who_api_key_credential WHERE key_hash = :keyHash")
                .param("keyHash", keyHash)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public ApiKeyCredential save(ApiKeyCredential credential) {
        jdbcClient
                .sql("""
                        INSERT INTO who_api_key_credential (id, key_hash)
                        VALUES (:id, :keyHash)
                        ON CONFLICT (id) DO UPDATE SET key_hash = :keyHash
                        """)
                .param("id", credential.id())
                .param("keyHash", credential.keyHash())
                .update();
        return credential;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient
                .sql("DELETE FROM who_api_key_credential WHERE id = :id")
                .param("id", id)
                .update();
    }
}
