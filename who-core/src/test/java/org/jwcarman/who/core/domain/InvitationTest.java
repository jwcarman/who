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
package org.jwcarman.who.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationTest {

    @Test
    void isExpired_returnsTrueWhenPastExpirationTime() {
        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            "alice@example.com",
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now().minusSeconds(100),
            Instant.now().minusSeconds(10),  // Expired 10 seconds ago
            null
        );

        assertThat(invitation.isExpired()).isTrue();
    }

    @Test
    void isExpired_returnsFalseWhenBeforeExpirationTime() {
        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            "alice@example.com",
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),  // Expires in 1 hour
            null
        );

        assertThat(invitation.isExpired()).isFalse();
    }

    @Test
    void isPending_returnsTrueWhenPendingAndNotExpired() {
        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            "alice@example.com",
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            null
        );

        assertThat(invitation.isPending()).isTrue();
    }

    @Test
    void isPending_returnsFalseWhenExpired() {
        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            "alice@example.com",
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            UUID.randomUUID(),
            Instant.now().minusSeconds(100),
            Instant.now().minusSeconds(10),
            null
        );

        assertThat(invitation.isPending()).isFalse();
    }

    @Test
    void isPending_returnsFalseWhenNotPendingStatus() {
        Invitation invitation = new Invitation(
            UUID.randomUUID(),
            "alice@example.com",
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            InvitationStatus.ACCEPTED,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now().plusSeconds(3600),
            Instant.now()
        );

        assertThat(invitation.isPending()).isFalse();
    }

    @Test
    void create_shouldNormalizeEmail() {
        Invitation invitation = Invitation.create("  Alice@EXAMPLE.COM  ",
            UUID.randomUUID(), UUID.randomUUID(), 24);

        assertThat(invitation.email()).isEqualTo("alice@example.com");
    }

    @Test
    void create_shouldSetPendingStatusAndNullAcceptedAt() {
        Invitation invitation = Invitation.create("test@example.com",
            UUID.randomUUID(), UUID.randomUUID(), 24);

        assertThat(invitation.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.acceptedAt()).isNull();
    }

    @Test
    void accept_shouldSetAcceptedStatusAndTimestamp() {
        Invitation pending = Invitation.create("test@example.com",
            UUID.randomUUID(), UUID.randomUUID(), 24);

        Instant before = Instant.now();
        Invitation accepted = pending.accept();
        Instant after = Instant.now();

        assertThat(accepted.status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(accepted.acceptedAt()).isBetween(before, after);
        assertThat(accepted.id()).isEqualTo(pending.id());
        assertThat(accepted.email()).isEqualTo(pending.email());
    }

    @Test
    void revoke_shouldSetRevokedStatus() {
        Invitation pending = Invitation.create("test@example.com",
            UUID.randomUUID(), UUID.randomUUID(), 24);

        Invitation revoked = pending.revoke();

        assertThat(revoked.status()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(revoked.acceptedAt()).isNull();
        assertThat(revoked.id()).isEqualTo(pending.id());
    }
}
