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
package org.jwcarman.who.jdbc;

import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link CredentialIdentityRepository} backed by the
 * {@code who_credential_identity} table.
 */
@Repository
public class JdbcCredentialIdentityRepository implements CredentialIdentityRepository {

    private final JdbcClient jdbcClient;

    public JdbcCredentialIdentityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<UUID> findIdentityIdByCredentialId(UUID credentialId) {
        return jdbcClient
                .sql("SELECT identity_id FROM who_credential_identity WHERE credential_id = :credentialId")
                .param("credentialId", credentialId)
                .query((rs, rowNum) -> rs.getObject("identity_id", UUID.class))
                .optional();
    }

    @Override
    public void link(UUID credentialId, UUID identityId) {
        jdbcClient
                .sql("INSERT INTO who_credential_identity (credential_id, identity_id) VALUES (:credentialId, :identityId)")
                .param("credentialId", credentialId)
                .param("identityId", identityId)
                .update();
    }

    @Override
    public void unlink(UUID credentialId) {
        jdbcClient
                .sql("DELETE FROM who_credential_identity WHERE credential_id = :credentialId")
                .param("credentialId", credentialId)
                .update();
    }
}
