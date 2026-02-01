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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;
import org.jwcarman.who.core.domain.User;
import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.jdbc.JdbcTestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = JdbcTestConfiguration.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
class JdbcContactMethodRepositoryTest {

    @Autowired
    private JdbcContactMethodRepository contactMethodRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private UUID testUserId;

    @BeforeEach
    void setUp() {
        // Create a test user
        testUserId = UUID.randomUUID();
        User testUser = User.create(testUserId, UserStatus.ACTIVE);
        jdbcClient.sql("INSERT INTO who_user (id, status, created_at, updated_at) VALUES (:id, :status, :createdAt, :updatedAt)")
            .param("id", testUser.id())
            .param("status", testUser.status().name())
            .param("createdAt", Timestamp.from(testUser.createdAt()))
            .param("updatedAt", Timestamp.from(testUser.updatedAt()))
            .update();
    }

    @Test
    void shouldSaveAndFindContactMethodById() {
        // Given
        ContactMethod contactMethod = ContactMethod.createUnverified(
            testUserId,
            ContactType.EMAIL,
            "test@example.com"
        );

        // When
        ContactMethod saved = contactMethodRepository.save(contactMethod);
        Optional<ContactMethod> found = contactMethodRepository.findById(contactMethod.id());

        // Then
        assertThat(saved).isEqualTo(contactMethod);
        assertThat(found).isPresent().contains(contactMethod);
    }

    @Test
    void shouldUpdateContactMethod() {
        // Given
        ContactMethod contactMethod = ContactMethod.createUnverified(
            testUserId,
            ContactType.EMAIL,
            "test@example.com"
        );
        contactMethodRepository.save(contactMethod);

        // When
        ContactMethod verified = contactMethod.markVerified();
        contactMethodRepository.save(verified);
        Optional<ContactMethod> found = contactMethodRepository.findById(contactMethod.id());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().verified()).isTrue();
        assertThat(found.get().verifiedAt()).isNotNull();
    }

    @Test
    void shouldReturnEmptyWhenContactMethodNotFoundById() {
        // When
        Optional<ContactMethod> found = contactMethodRepository.findById(UUID.randomUUID());

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindContactMethodsByUserId() {
        // Given
        ContactMethod email = ContactMethod.createUnverified(testUserId, ContactType.EMAIL, "test@example.com");
        ContactMethod phone = ContactMethod.createUnverified(testUserId, ContactType.PHONE, "+1234567890");

        contactMethodRepository.save(email);
        contactMethodRepository.save(phone);

        // When
        List<ContactMethod> found = contactMethodRepository.findByUserId(testUserId);

        // Then
        assertThat(found).hasSize(2)
            .extracting(ContactMethod::type)
            .containsExactlyInAnyOrder(ContactType.EMAIL, ContactType.PHONE);
    }

    @Test
    void shouldReturnEmptyListWhenNoContactMethodsForUser() {
        // When
        List<ContactMethod> found = contactMethodRepository.findByUserId(UUID.randomUUID());

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindContactMethodByUserIdAndType() {
        // Given
        ContactMethod email = ContactMethod.createUnverified(testUserId, ContactType.EMAIL, "test@example.com");
        contactMethodRepository.save(email);

        // When
        Optional<ContactMethod> found = contactMethodRepository.findByUserIdAndType(testUserId, ContactType.EMAIL);

        // Then
        assertThat(found).isPresent().contains(email);
    }

    @Test
    void shouldReturnEmptyWhenContactMethodNotFoundByUserIdAndType() {
        // When
        Optional<ContactMethod> found = contactMethodRepository.findByUserIdAndType(testUserId, ContactType.PHONE);

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindContactMethodByTypeAndValue() {
        // Given
        ContactMethod contactMethod = ContactMethod.createUnverified(
            testUserId,
            ContactType.EMAIL,
            "test@example.com"
        );
        contactMethodRepository.save(contactMethod);

        // When
        Optional<ContactMethod> found = contactMethodRepository.findByTypeAndValue(
            ContactType.EMAIL,
            "test@example.com"
        );

        // Then
        assertThat(found).isPresent().contains(contactMethod);
    }

    @Test
    void shouldReturnEmptyWhenContactMethodNotFoundByTypeAndValue() {
        // When
        Optional<ContactMethod> found = contactMethodRepository.findByTypeAndValue(
            ContactType.EMAIL,
            "nonexistent@example.com"
        );

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldDeleteContactMethod() {
        // Given
        ContactMethod contactMethod = ContactMethod.createUnverified(
            testUserId,
            ContactType.EMAIL,
            "test@example.com"
        );
        contactMethodRepository.save(contactMethod);

        // When
        contactMethodRepository.deleteById(contactMethod.id());

        // Then
        assertThat(contactMethodRepository.findById(contactMethod.id())).isEmpty();
    }

    @Test
    void shouldHandleEmailCaseInsensitivity() {
        // Given
        ContactMethod contactMethod = ContactMethod.createUnverified(
            testUserId,
            ContactType.EMAIL,
            "Test@Example.COM"
        );
        contactMethodRepository.save(contactMethod);

        // When
        Optional<ContactMethod> found = contactMethodRepository.findByTypeAndValue(
            ContactType.EMAIL,
            "test@example.com"
        );

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().value()).isEqualTo("test@example.com");
    }

    @Test
    void shouldNotFindContactMethodsForDifferentUser() {
        // Given
        UUID otherUserId = UUID.randomUUID();
        User otherUser = User.create(otherUserId, UserStatus.ACTIVE);
        jdbcClient.sql("INSERT INTO who_user (id, status, created_at, updated_at) VALUES (:id, :status, :createdAt, :updatedAt)")
            .param("id", otherUser.id())
            .param("status", otherUser.status().name())
            .param("createdAt", Timestamp.from(otherUser.createdAt()))
            .param("updatedAt", Timestamp.from(otherUser.updatedAt()))
            .update();

        ContactMethod contactMethod = ContactMethod.createUnverified(
            testUserId,
            ContactType.EMAIL,
            "test@example.com"
        );
        contactMethodRepository.save(contactMethod);

        // When
        List<ContactMethod> found = contactMethodRepository.findByUserId(otherUserId);

        // Then
        assertThat(found).isEmpty();
    }
}
