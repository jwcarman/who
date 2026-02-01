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

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;
import org.jwcarman.who.core.repository.InvitationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcInvitationRepository implements InvitationRepository {

    private final JdbcClient jdbcClient;

    public JdbcInvitationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Invitation save(Invitation invitation) {
        int updated = jdbcClient.sql("""
                UPDATE who_invitation
                SET email = :email,
                    role_id = :roleId,
                    token = :token,
                    status = :status,
                    invited_by = :invitedBy,
                    created_at = :createdAt,
                    expires_at = :expiresAt,
                    accepted_at = :acceptedAt
                WHERE id = :id
                """)
            .param("id", invitation.id())
            .param("email", invitation.email())
            .param("roleId", invitation.roleId())
            .param("token", invitation.token())
            .param("status", invitation.status().name())
            .param("invitedBy", invitation.invitedBy())
            .param("createdAt", Timestamp.from(invitation.createdAt()))
            .param("expiresAt", Timestamp.from(invitation.expiresAt()))
            .param("acceptedAt", invitation.acceptedAt() != null ? Timestamp.from(invitation.acceptedAt()) : null)
            .update();

        if (updated == 0) {
            // Insert new invitation
            jdbcClient.sql("""
                    INSERT INTO who_invitation (id, email, role_id, token, status, invited_by, created_at, expires_at, accepted_at)
                    VALUES (:id, :email, :roleId, :token, :status, :invitedBy, :createdAt, :expiresAt, :acceptedAt)
                    """)
                .param("id", invitation.id())
                .param("email", invitation.email())
                .param("roleId", invitation.roleId())
                .param("token", invitation.token())
                .param("status", invitation.status().name())
                .param("invitedBy", invitation.invitedBy())
                .param("createdAt", Timestamp.from(invitation.createdAt()))
                .param("expiresAt", Timestamp.from(invitation.expiresAt()))
                .param("acceptedAt", invitation.acceptedAt() != null ? Timestamp.from(invitation.acceptedAt()) : null)
                .update();
        }

        return invitation;
    }

    @Override
    public Optional<Invitation> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, email, role_id, token, status, invited_by, created_at, expires_at, accepted_at
                FROM who_invitation
                WHERE id = :id
                """)
            .param("id", id)
            .query(this::mapRow)
            .optional();
    }

    @Override
    public Optional<Invitation> findByToken(String token) {
        return jdbcClient.sql("""
                SELECT id, email, role_id, token, status, invited_by, created_at, expires_at, accepted_at
                FROM who_invitation
                WHERE token = :token
                """)
            .param("token", token)
            .query(this::mapRow)
            .optional();
    }

    @Override
    public Optional<Invitation> findPendingByEmail(String email) {
        return jdbcClient.sql("""
                SELECT id, email, role_id, token, status, invited_by, created_at, expires_at, accepted_at
                FROM who_invitation
                WHERE email = :email AND status = 'PENDING'
                """)
            .param("email", email.toLowerCase().trim())
            .query(this::mapRow)
            .optional();
    }

    @Override
    public List<Invitation> findByStatusAndSince(InvitationStatus status, Instant since) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, email, role_id, token, status, invited_by, created_at, expires_at, accepted_at
                FROM who_invitation
                WHERE 1=1
                """);

        JdbcClient.StatementSpec spec = jdbcClient.sql("");

        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (since != null) {
            sql.append(" AND created_at >= :since");
        }

        spec = jdbcClient.sql(sql.toString());

        if (status != null) {
            spec = spec.param("status", status.name());
        }
        if (since != null) {
            spec = spec.param("since", Timestamp.from(since));
        }

        return spec.query(this::mapRow).list();
    }

    @Override
    public void deleteById(UUID id) {
        jdbcClient.sql("""
                DELETE FROM who_invitation WHERE id = :id
                """)
            .param("id", id)
            .update();
    }

    private Invitation mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp acceptedAtTimestamp = rs.getTimestamp("accepted_at");
        return new Invitation(
            UUID.fromString(rs.getString("id")),
            rs.getString("email"),
            UUID.fromString(rs.getString("role_id")),
            rs.getString("token"),
            InvitationStatus.valueOf(rs.getString("status")),
            UUID.fromString(rs.getString("invited_by")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            acceptedAtTimestamp != null ? acceptedAtTimestamp.toInstant() : null
        );
    }
}
