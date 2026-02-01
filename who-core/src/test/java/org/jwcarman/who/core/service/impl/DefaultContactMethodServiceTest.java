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
package org.jwcarman.who.core.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.core.repository.ContactMethodRepository;
import org.jwcarman.who.core.repository.UserRepository;
import org.jwcarman.who.core.service.ContactMethodService;
import org.jwcarman.who.core.spi.ContactConfirmationNotifier;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultContactMethodServiceTest {

    @Mock
    private ContactMethodRepository repository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContactConfirmationNotifier notifier;

    private ContactMethodService service;

    @BeforeEach
    void setUp() {
        service = new DefaultContactMethodService(repository, userRepository, notifier);
    }

    @Test
    void createUnverified_createsAndSavesUnverifiedContactMethod() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, UserStatus.ACTIVE, Instant.now(), Instant.now());
        ContactMethod savedContact = ContactMethod.createUnverified(userId, ContactType.EMAIL, "alice@example.com");

        when(repository.save(any(ContactMethod.class))).thenReturn(savedContact);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        ContactMethod result = service.createUnverified(userId, ContactType.EMAIL, "alice@example.com");

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.type()).isEqualTo(ContactType.EMAIL);
        assertThat(result.value()).isEqualTo("alice@example.com");
        assertThat(result.verified()).isFalse();
        verify(repository).save(any(ContactMethod.class));
        verify(userRepository).findById(userId);
        verify(notifier).notifyContactAdded(savedContact, user);
    }

    @Test
    void createVerified_createsAndSavesVerifiedContactMethod() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, UserStatus.ACTIVE, Instant.now(), Instant.now());
        ContactMethod savedContact = ContactMethod.createVerified(userId, ContactType.EMAIL, "bob@example.com");

        when(repository.save(any(ContactMethod.class))).thenReturn(savedContact);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        ContactMethod result = service.createVerified(userId, ContactType.EMAIL, "bob@example.com");

        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.type()).isEqualTo(ContactType.EMAIL);
        assertThat(result.value()).isEqualTo("bob@example.com");
        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedAt()).isNotNull();
        verify(repository).save(any(ContactMethod.class));
        verify(userRepository).findById(userId);
        verify(notifier).notifyContactAdded(savedContact, user);
    }

    @Test
    void markVerified_findsContactMethodMarksVerifiedAndSaves() {
        UUID contactId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ContactMethod unverified = ContactMethod.createUnverified(userId, ContactType.EMAIL, "alice@example.com");
        ContactMethod verified = unverified.markVerified();

        when(repository.findById(contactId)).thenReturn(Optional.of(unverified));
        when(repository.save(any(ContactMethod.class))).thenReturn(verified);

        ContactMethod result = service.markVerified(contactId);

        assertThat(result).isNotNull();
        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedAt()).isNotNull();
        verify(repository).findById(contactId);
        verify(repository).save(any(ContactMethod.class));
    }

    @Test
    void markVerified_throwsExceptionWhenContactMethodNotFound() {
        UUID contactId = UUID.randomUUID();

        when(repository.findById(contactId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markVerified(contactId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Contact method not found");
    }

    @Test
    void findByUserId_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        List<ContactMethod> contacts = List.of(
            ContactMethod.createUnverified(userId, ContactType.EMAIL, "alice@example.com"),
            ContactMethod.createUnverified(userId, ContactType.PHONE, "+1234567890")
        );

        when(repository.findByUserId(userId)).thenReturn(contacts);

        List<ContactMethod> result = service.findByUserId(userId);

        assertThat(result).hasSize(2).isEqualTo(contacts);
        verify(repository).findByUserId(userId);
    }

    @Test
    void findByUserIdAndType_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        ContactMethod contact = ContactMethod.createUnverified(userId, ContactType.EMAIL, "alice@example.com");

        when(repository.findByUserIdAndType(userId, ContactType.EMAIL)).thenReturn(Optional.of(contact));

        Optional<ContactMethod> result = service.findByUserIdAndType(userId, ContactType.EMAIL);

        assertThat(result).contains(contact);
        verify(repository).findByUserIdAndType(userId, ContactType.EMAIL);
    }

    @Test
    void findByUserIdAndType_returnsEmptyWhenNotFound() {
        UUID userId = UUID.randomUUID();

        when(repository.findByUserIdAndType(userId, ContactType.EMAIL)).thenReturn(Optional.empty());

        Optional<ContactMethod> result = service.findByUserIdAndType(userId, ContactType.EMAIL);

        assertThat(result).isEmpty();
        verify(repository).findByUserIdAndType(userId, ContactType.EMAIL);
    }

    @Test
    void delete_validatesContactExistsAndDeletes() {
        UUID contactId = UUID.randomUUID();
        ContactMethod contact = ContactMethod.createUnverified(UUID.randomUUID(), ContactType.EMAIL, "alice@example.com");

        when(repository.findById(contactId)).thenReturn(Optional.of(contact));

        service.delete(contactId);

        verify(repository).findById(contactId);
        verify(repository).deleteById(contactId);
    }

    @Test
    void delete_throwsExceptionWhenContactMethodNotFound() {
        UUID contactId = UUID.randomUUID();

        when(repository.findById(contactId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(contactId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Contact method not found");
    }
}
