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

class ContactMethodTest {

    @Test
    void createUnverified_createsWithVerifiedFalse() {
        UUID userId = UUID.randomUUID();

        ContactMethod contact = ContactMethod.createUnverified(
            userId,
            ContactType.EMAIL,
            "Alice@Example.COM"  // Should be normalized
        );

        assertThat(contact.userId()).isEqualTo(userId);
        assertThat(contact.type()).isEqualTo(ContactType.EMAIL);
        assertThat(contact.value()).isEqualTo("alice@example.com");
        assertThat(contact.verified()).isFalse();
        assertThat(contact.verifiedAt()).isNull();
    }

    @Test
    void markVerified_setsVerifiedTrueAndTimestamp() {
        ContactMethod unverified = ContactMethod.createUnverified(
            UUID.randomUUID(),
            ContactType.EMAIL,
            "alice@example.com"
        );

        Instant before = Instant.now();
        ContactMethod verified = unverified.markVerified();
        Instant after = Instant.now();

        assertThat(verified.verified()).isTrue();
        assertThat(verified.verifiedAt()).isNotNull();
        assertThat(verified.verifiedAt()).isBetween(before, after);
        assertThat(verified.id()).isEqualTo(unverified.id());
        assertThat(verified.value()).isEqualTo(unverified.value());
    }

    @Test
    void createVerified_createsWithVerifiedTrue() {
        UUID userId = UUID.randomUUID();

        Instant before = Instant.now();
        ContactMethod contact = ContactMethod.createVerified(
            userId,
            ContactType.EMAIL,
            "bob@example.com"
        );
        Instant after = Instant.now();

        assertThat(contact.userId()).isEqualTo(userId);
        assertThat(contact.type()).isEqualTo(ContactType.EMAIL);
        assertThat(contact.value()).isEqualTo("bob@example.com");
        assertThat(contact.verified()).isTrue();
        assertThat(contact.verifiedAt()).isNotNull();
        assertThat(contact.verifiedAt()).isBetween(before, after);
    }

    @Test
    void emailNormalization_handlesWhitespace() {
        ContactMethod contact = ContactMethod.createUnverified(
            UUID.randomUUID(),
            ContactType.EMAIL,
            "  alice@example.com  "
        );

        assertThat(contact.value()).isEqualTo("alice@example.com");
    }

    @Test
    void emailNormalization_handlesMixedCase() {
        ContactMethod contact = ContactMethod.createUnverified(
            UUID.randomUUID(),
            ContactType.EMAIL,
            "AlIcE@ExAmPlE.CoM"
        );

        assertThat(contact.value()).isEqualTo("alice@example.com");
    }

    @Test
    void phoneNormalization_trimsWhitespace() {
        ContactMethod contact = ContactMethod.createUnverified(
            UUID.randomUUID(),
            ContactType.PHONE,
            "  +1234567890  "
        );

        assertThat(contact.value()).isEqualTo("+1234567890");
    }

    @Test
    void markVerified_preservesAllFieldsExceptVerification() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(3600);

        ContactMethod unverified = new ContactMethod(
            id,
            userId,
            ContactType.EMAIL,
            "alice@example.com",
            false,
            null,
            createdAt
        );

        ContactMethod verified = unverified.markVerified();

        assertThat(verified.id()).isEqualTo(id);
        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.type()).isEqualTo(ContactType.EMAIL);
        assertThat(verified.value()).isEqualTo("alice@example.com");
        assertThat(verified.createdAt()).isEqualTo(createdAt);
    }
}
