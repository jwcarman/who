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
package org.jwcarman.who.jwt;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of {@link JwtCredentialRepository} backed by the {@code who_jwt_credential} table.
 */
@Repository
public class JdbcJwtCredentialRepository implements JwtCredentialRepository {

    private static final String COL_ISSUER = "issuer";
    private static final String COL_SUBJECT = "subject";

    private static final RowMapper<JwtCredential> ROW_MAPPER = (rs, rowNum) ->
            new JwtCredential(
                    rs.getObject("id", UUID.class),
                    rs.getString(COL_ISSUER),
                    rs.getString(COL_SUBJECT));

    private final JdbcClient jdbcClient;

    public JdbcJwtCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<JwtCredential> findByIssuerAndSubject(String issuer, String subject) {
        return jdbcClient
                .sql("SELECT id, issuer, subject FROM who_jwt_credential WHERE issuer = :issuer AND subject = :subject")
                .param(COL_ISSUER, issuer)
                .param(COL_SUBJECT, subject)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public JwtCredential save(JwtCredential credential) {
        jdbcClient
                .sql("""
                        INSERT INTO who_jwt_credential (id, issuer, subject)
                        VALUES (:id, :issuer, :subject)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", credential.id())
                .param(COL_ISSUER, credential.issuer())
                .param(COL_SUBJECT, credential.subject())
                .update();
        return credential;
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient
                .sql("DELETE FROM who_jwt_credential WHERE id = :id")
                .param("id", id)
                .update();
    }
}
