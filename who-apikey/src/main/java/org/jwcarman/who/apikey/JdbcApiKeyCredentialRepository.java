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

    private static final String COL_ID = "id";
    private static final String COL_NAME = "name";
    private static final String COL_KEY_HASH = "key_hash";
    private static final String PARAM_KEY_HASH = "keyHash";

    private static final RowMapper<ApiKeyCredential> ROW_MAPPER = (rs, rowNum) ->
            new ApiKeyCredential(
                    rs.getObject(COL_ID, UUID.class),
                    rs.getString(COL_NAME),
                    rs.getString(COL_KEY_HASH));

    private final JdbcClient jdbcClient;

    /**
     * Creates a new {@code JdbcApiKeyCredentialRepository}.
     *
     * @param jdbcClient the JDBC client used to execute queries
     */
    public JdbcApiKeyCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<ApiKeyCredential> findByKeyHash(String keyHash) {
        return jdbcClient
                .sql("SELECT id, name, key_hash FROM who_api_key_credential WHERE key_hash = :keyHash")
                .param(PARAM_KEY_HASH, keyHash)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public ApiKeyCredential save(ApiKeyCredential credential) {
        jdbcClient
                .sql("""
                        INSERT INTO who_api_key_credential (id, name, key_hash)
                        VALUES (:id, :name, :keyHash)
                        ON CONFLICT (id) DO UPDATE SET name = :name, key_hash = :keyHash
                        """)
                .param(COL_ID, credential.id())
                .param(COL_NAME, credential.name())
                .param(PARAM_KEY_HASH, credential.keyHash())
                .update();
        return credential;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient
                .sql("DELETE FROM who_api_key_credential WHERE id = :id")
                .param(COL_ID, id)
                .update();
    }
}
