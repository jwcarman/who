# User Invitation System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement email-based user invitation system with OAuth acceptance, contact method management, and auto-configured security

**Architecture:** Domain models (Invitation, ContactMethod) + JPA entities + Services (InvitationService, ContactMethodService) + SPIs (InvitationNotifier, ContactVerificationNotifier, ContactConfirmationNotifier) + REST controller + Security autoconfiguration

**Tech Stack:** Spring Boot 4.0, Spring Security 7.0, Spring Data JPA, JUnit 5, Mockito

---

## Task 1: Add InvitationStatus Enum (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/InvitationStatus.java`

**Step 1: Create InvitationStatus enum**

```java
package org.jwcarman.who.core.domain;

/**
 * Status of an invitation.
 */
public enum InvitationStatus {
    /** Invitation created, awaiting acceptance */
    PENDING,

    /** Invitation accepted, user created */
    ACCEPTED,

    /** Invitation expired (past expiration timestamp) */
    EXPIRED,

    /** Invitation revoked by admin before acceptance */
    REVOKED
}
```

**Step 2: Commit**

```bash
cd who-core
git add src/main/java/org/jwcarman/who/core/domain/InvitationStatus.java
git commit -m "feat(core): add InvitationStatus enum

Defines lifecycle states for invitations: PENDING, ACCEPTED, EXPIRED, REVOKED

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 2: Add Invitation Domain Model (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/Invitation.java`
- Create: `who-core/src/test/java/org/jwcarman/who/core/domain/InvitationTest.java`

**Step 1: Write failing test**

```java
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
}
```

**Step 2: Run test to verify it fails**

```bash
cd who-core
mvn test -Dtest=InvitationTest
```

Expected: FAIL with "cannot find symbol: class Invitation"

**Step 3: Create Invitation record**

```java
package org.jwcarman.who.core.domain;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable invitation domain model.
 */
public record Invitation(
    UUID id,
    String email,              // Invited email (normalized: lowercase, trimmed)
    UUID roleId,               // Role to assign on acceptance
    String token,              // Unique acceptance token (random UUID)
    InvitationStatus status,   // PENDING, ACCEPTED, EXPIRED, REVOKED
    UUID invitedBy,            // Admin user who created invite
    Instant createdAt,
    Instant expiresAt,
    Instant acceptedAt         // Null until accepted
) {
    public Invitation {
        requireNonNull(id, "id must not be null");
        requireNonNull(email, "email must not be null");
        requireNonNull(roleId, "roleId must not be null");
        requireNonNull(token, "token must not be null");
        requireNonNull(status, "status must not be null");
        requireNonNull(invitedBy, "invitedBy must not be null");
        requireNonNull(createdAt, "createdAt must not be null");
        requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * Check if invitation has expired.
     *
     * @return true if current time is after expiration time
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if invitation is pending (PENDING status and not expired).
     *
     * @return true if pending and not expired
     */
    public boolean isPending() {
        return status == InvitationStatus.PENDING && !isExpired();
    }

    /**
     * Create a new pending invitation.
     *
     * @param email the email to invite
     * @param roleId the role to assign
     * @param invitedBy the admin creating the invitation
     * @param expirationHours hours until expiration
     * @return new pending invitation
     */
    public static Invitation create(String email, UUID roleId, UUID invitedBy, int expirationHours) {
        Instant now = Instant.now();
        return new Invitation(
            UUID.randomUUID(),
            email.toLowerCase().trim(),
            roleId,
            UUID.randomUUID().toString(),
            InvitationStatus.PENDING,
            invitedBy,
            now,
            now.plusSeconds(expirationHours * 3600L),
            null
        );
    }

    /**
     * Mark invitation as accepted.
     *
     * @return new invitation with ACCEPTED status and acceptedAt timestamp
     */
    public Invitation accept() {
        return new Invitation(
            id, email, roleId, token,
            InvitationStatus.ACCEPTED,
            invitedBy, createdAt, expiresAt,
            Instant.now()
        );
    }

    /**
     * Mark invitation as revoked.
     *
     * @return new invitation with REVOKED status
     */
    public Invitation revoke() {
        return new Invitation(
            id, email, roleId, token,
            InvitationStatus.REVOKED,
            invitedBy, createdAt, expiresAt,
            acceptedAt
        );
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=InvitationTest
```

Expected: PASS (5 tests)

**Step 5: Commit**

```bash
git add src/main/java/org/jwcarman/who/core/domain/Invitation.java src/test/java/org/jwcarman/who/core/domain/InvitationTest.java
git commit -m "feat(core): add Invitation domain model

Immutable record with:
- Email normalization (lowercase, trim)
- Status lifecycle (PENDING -> ACCEPTED/REVOKED)
- Expiration checking
- Factory methods for creation and state transitions

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 3: Add ContactType Enum and ContactMethod Domain Model (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/ContactType.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/domain/ContactMethod.java`
- Create: `who-core/src/test/java/org/jwcarman/who/core/domain/ContactMethodTest.java`

**Step 1: Create ContactType enum**

```java
package org.jwcarman.who.core.domain;

/**
 * Type of contact method.
 */
public enum ContactType {
    /** Email address */
    EMAIL,

    /** Phone number */
    PHONE
}
```

**Step 2: Write failing test for ContactMethod**

```java
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
}
```

**Step 3: Run test to verify it fails**

```bash
mvn test -Dtest=ContactMethodTest
```

Expected: FAIL with "cannot find symbol: class ContactMethod"

**Step 4: Create ContactMethod record**

```java
package org.jwcarman.who.core.domain;

import java.time.Instant;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable contact method domain model.
 */
public record ContactMethod(
    UUID id,
    UUID userId,
    ContactType type,           // EMAIL, PHONE
    String value,               // Normalized (lowercase email, E.164 phone)
    boolean verified,           // True if user confirmed ownership
    Instant verifiedAt,         // When verification completed (null if unverified)
    Instant createdAt
) {
    public ContactMethod {
        requireNonNull(id, "id must not be null");
        requireNonNull(userId, "userId must not be null");
        requireNonNull(type, "type must not be null");
        requireNonNull(value, "value must not be null");
        requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Create unverified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value (will be normalized)
     * @return new unverified contact method
     */
    public static ContactMethod createUnverified(UUID userId, ContactType type, String value) {
        return new ContactMethod(
            UUID.randomUUID(),
            userId,
            type,
            normalize(value, type),
            false,
            null,
            Instant.now()
        );
    }

    /**
     * Create verified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value (will be normalized)
     * @return new verified contact method
     */
    public static ContactMethod createVerified(UUID userId, ContactType type, String value) {
        Instant now = Instant.now();
        return new ContactMethod(
            UUID.randomUUID(),
            userId,
            type,
            normalize(value, type),
            true,
            now,
            now
        );
    }

    /**
     * Mark contact method as verified.
     *
     * @return new contact method with verified=true and verifiedAt timestamp
     */
    public ContactMethod markVerified() {
        return new ContactMethod(
            id, userId, type, value,
            true,
            Instant.now(),
            createdAt
        );
    }

    /**
     * Normalize contact value based on type.
     *
     * @param value the raw value
     * @param type the contact type
     * @return normalized value
     */
    private static String normalize(String value, ContactType type) {
        requireNonNull(value, "value must not be null");
        return switch (type) {
            case EMAIL -> value.toLowerCase().trim();
            case PHONE -> value.trim();  // Phone normalization (E.164) left for future
        };
    }
}
```

**Step 5: Run tests to verify they pass**

```bash
mvn test -Dtest=ContactMethodTest
```

Expected: PASS (2 tests)

**Step 6: Commit**

```bash
git add src/main/java/org/jwcarman/who/core/domain/ContactType.java \
        src/main/java/org/jwcarman/who/core/domain/ContactMethod.java \
        src/test/java/org/jwcarman/who/core/domain/ContactMethodTest.java
git commit -m "feat(core): add ContactMethod domain model

Immutable record with:
- EMAIL and PHONE support
- Value normalization (lowercase for email)
- Verification status and timestamp
- Factory methods for unverified/verified creation

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: Add Invitation Exception Types (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/exception/InvitationNotFoundException.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/exception/InvitationExpiredException.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/exception/InvitationAlreadyAcceptedException.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/exception/EmailMismatchException.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/exception/EmailNotVerifiedException.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/exception/UserAlreadyExistsException.java`

**Step 1: Create exception classes**

```java
// InvitationNotFoundException.java
package org.jwcarman.who.core.exception;

public class InvitationNotFoundException extends RuntimeException {
    public InvitationNotFoundException(String message) {
        super(message);
    }
}

// InvitationExpiredException.java
package org.jwcarman.who.core.exception;

public class InvitationExpiredException extends RuntimeException {
    public InvitationExpiredException(String message) {
        super(message);
    }
}

// InvitationAlreadyAcceptedException.java
package org.jwcarman.who.core.exception;

public class InvitationAlreadyAcceptedException extends RuntimeException {
    public InvitationAlreadyAcceptedException(String message) {
        super(message);
    }
}

// EmailMismatchException.java
package org.jwcarman.who.core.exception;

public class EmailMismatchException extends RuntimeException {
    public EmailMismatchException(String message) {
        super(message);
    }
}

// EmailNotVerifiedException.java
package org.jwcarman.who.core.exception;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException(String message) {
        super(message);
    }
}

// UserAlreadyExistsException.java
package org.jwcarman.who.core.exception;

public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
```

**Step 2: Commit**

```bash
cd who-core
git add src/main/java/org/jwcarman/who/core/exception/
git commit -m "feat(core): add invitation exception types

Six exception types for invitation lifecycle:
- InvitationNotFoundException (404)
- InvitationExpiredException (410)
- InvitationAlreadyAcceptedException (409)
- EmailMismatchException (403)
- EmailNotVerifiedException (401)
- UserAlreadyExistsException (409)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: Add Invitation and ContactMethod Repositories (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/repository/InvitationRepository.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/repository/ContactMethodRepository.java`

**Step 1: Create InvitationRepository**

```java
package org.jwcarman.who.core.repository;

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing invitations.
 */
public interface InvitationRepository {

    /**
     * Save an invitation.
     *
     * @param invitation the invitation to save
     * @return the saved invitation
     */
    Invitation save(Invitation invitation);

    /**
     * Find invitation by ID.
     *
     * @param id the invitation ID
     * @return the invitation if found
     */
    Optional<Invitation> findById(UUID id);

    /**
     * Find invitation by token.
     *
     * @param token the invitation token
     * @return the invitation if found
     */
    Optional<Invitation> findByToken(String token);

    /**
     * Find pending invitation by email.
     *
     * @param email the email address
     * @return the pending invitation if found
     */
    Optional<Invitation> findPendingByEmail(String email);

    /**
     * Find invitations by status.
     *
     * @param status the invitation status (null for all)
     * @param since filter by created after this time (null for all)
     * @return list of invitations
     */
    List<Invitation> findByStatusAndSince(InvitationStatus status, Instant since);

    /**
     * Delete an invitation.
     *
     * @param id the invitation ID
     */
    void deleteById(UUID id);
}
```

**Step 2: Create ContactMethodRepository**

```java
package org.jwcarman.who.core.repository;

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing contact methods.
 */
public interface ContactMethodRepository {

    /**
     * Save a contact method.
     *
     * @param contactMethod the contact method to save
     * @return the saved contact method
     */
    ContactMethod save(ContactMethod contactMethod);

    /**
     * Find contact method by ID.
     *
     * @param id the contact method ID
     * @return the contact method if found
     */
    Optional<ContactMethod> findById(UUID id);

    /**
     * Find contact methods by user ID.
     *
     * @param userId the user ID
     * @return list of contact methods
     */
    List<ContactMethod> findByUserId(UUID userId);

    /**
     * Find contact method by user ID and type.
     *
     * @param userId the user ID
     * @param type the contact type
     * @return the contact method if found
     */
    Optional<ContactMethod> findByUserIdAndType(UUID userId, ContactType type);

    /**
     * Find contact method by type and value.
     *
     * @param type the contact type
     * @param value the contact value (normalized)
     * @return the contact method if found
     */
    Optional<ContactMethod> findByTypeAndValue(ContactType type, String value);

    /**
     * Delete a contact method.
     *
     * @param id the contact method ID
     */
    void deleteById(UUID id);
}
```

**Step 3: Commit**

```bash
git add src/main/java/org/jwcarman/who/core/repository/InvitationRepository.java \
        src/main/java/org/jwcarman/who/core/repository/ContactMethodRepository.java
git commit -m "feat(core): add Invitation and ContactMethod repositories

InvitationRepository supports:
- Token and email lookup
- Status and date filtering
- Pending invitation queries

ContactMethodRepository supports:
- User and type queries
- Value uniqueness checks

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: Add SPI Interfaces (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/spi/InvitationNotifier.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/spi/ContactVerificationNotifier.java`
- Create: `who-core/src/main/java/org/jwcarman/who/core/spi/ContactConfirmationNotifier.java`

**Step 1: Create InvitationNotifier**

```java
package org.jwcarman.who.core.spi;

import org.jwcarman.who.core.domain.Invitation;

/**
 * SPI for sending invitation notifications.
 * Application must provide a bean implementing this interface.
 */
public interface InvitationNotifier {

    /**
     * Send invitation email to user.
     * Application constructs the acceptance URL based on its own deployment.
     *
     * @param invitation the invitation details (includes token, email, expiration, role)
     */
    void sendInvitation(Invitation invitation);
}
```

**Step 2: Create ContactVerificationNotifier**

```java
package org.jwcarman.who.core.spi;

import org.jwcarman.who.core.domain.ContactMethod;

/**
 * SPI for sending contact verification codes.
 * Required if trust-issuer-verification is false or when manual verification is needed.
 */
public interface ContactVerificationNotifier {

    /**
     * Send verification code to contact method.
     *
     * @param contact the contact method to verify
     * @param code the verification code (generated by Who)
     */
    void sendVerificationCode(ContactMethod contact, String code);
}
```

**Step 3: Create ContactConfirmationNotifier**

```java
package org.jwcarman.who.core.spi;

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.User;

/**
 * SPI for notifying users when contact methods are added.
 * Sends "if this wasn't you" security notifications.
 * Required if notify-on-contact-add is true.
 */
public interface ContactConfirmationNotifier {

    /**
     * Notify user that a contact method was added to their account.
     * User can revoke if this wasn't them.
     *
     * @param contact the contact method that was added
     * @param user the user it was added to
     */
    void notifyContactAdded(ContactMethod contact, User user);
}
```

**Step 4: Commit**

```bash
git add src/main/java/org/jwcarman/who/core/spi/
git commit -m "feat(core): add notification SPI interfaces

Three SPIs for application integration:
- InvitationNotifier (required always)
- ContactVerificationNotifier (required conditionally)
- ContactConfirmationNotifier (required if notify-on-contact-add=true)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 7: Add InvitationService Interface (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/InvitationService.java`

**Step 1: Create InvitationService**

```java
package org.jwcarman.who.core.service;

import org.jwcarman.who.core.domain.Invitation;
import org.jwcarman.who.core.domain.InvitationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing user invitations.
 * Extracts current user (WhoPrincipal) from Spring SecurityContext for audit fields.
 */
public interface InvitationService {

    /**
     * Create invitation for email with role.
     * Auto-revokes any existing PENDING invitation for this email.
     * Current user extracted from SecurityContext.
     *
     * @param email the email to invite (will be normalized)
     * @param roleId the role to assign on acceptance
     * @return the created invitation
     * @throws org.jwcarman.who.core.exception.UserAlreadyExistsException if user already exists with this email
     */
    Invitation create(String email, UUID roleId);

    /**
     * Accept invitation after OAuth authentication.
     * Extracts JWT claims from SecurityContext (JwtAuthenticationToken).
     * Creates User, links ExternalIdentity, assigns Role, optionally creates verified ContactMethod.
     *
     * @param token the invitation token
     * @return the accepted invitation
     * @throws org.jwcarman.who.core.exception.InvitationNotFoundException if token invalid
     * @throws org.jwcarman.who.core.exception.InvitationExpiredException if expired
     * @throws org.jwcarman.who.core.exception.InvitationAlreadyAcceptedException if already used
     * @throws org.jwcarman.who.core.exception.EmailMismatchException if JWT email doesn't match invitation email
     * @throws org.jwcarman.who.core.exception.EmailNotVerifiedException if require-verified-email is true but email not verified
     * @throws IllegalStateException if SecurityContext doesn't contain JwtAuthenticationToken
     */
    Invitation accept(String token);

    /**
     * Revoke pending invitation.
     * Current user extracted from SecurityContext.
     *
     * @param invitationId the invitation to revoke
     * @throws org.jwcarman.who.core.exception.InvitationNotFoundException if not found
     */
    void revoke(UUID invitationId);

    /**
     * List invitations with optional filtering.
     *
     * @param status filter by status (null for all)
     * @param since filter by created after this time (null for all)
     * @return list of invitations
     */
    List<Invitation> list(InvitationStatus status, Instant since);

    /**
     * Get invitation by token (for validation).
     *
     * @param token the invitation token
     * @return the invitation if found
     */
    Optional<Invitation> findByToken(String token);
}
```

**Step 2: Commit**

```bash
git add src/main/java/org/jwcarman/who/core/service/InvitationService.java
git commit -m "feat(core): add InvitationService interface

Spring-aware service that:
- Creates invitations with auto-revocation
- Accepts invitations via JWT extraction from SecurityContext
- Revokes pending invitations
- Lists/filters invitations

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: Add ContactMethodService Interface (who-core)

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/service/ContactMethodService.java`

**Step 1: Create ContactMethodService**

```java
package org.jwcarman.who.core.service;

import org.jwcarman.who.core.domain.ContactMethod;
import org.jwcarman.who.core.domain.ContactType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing user contact methods.
 */
public interface ContactMethodService {

    /**
     * Create unverified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value
     * @return the created contact method
     */
    ContactMethod createUnverified(UUID userId, ContactType type, String value);

    /**
     * Create verified contact method.
     *
     * @param userId the user ID
     * @param type the contact type
     * @param value the contact value
     * @return the created contact method
     */
    ContactMethod createVerified(UUID userId, ContactType type, String value);

    /**
     * Mark contact method as verified.
     *
     * @param contactMethodId the contact method ID
     * @return the verified contact method
     * @throws IllegalArgumentException if contact method not found
     */
    ContactMethod markVerified(UUID contactMethodId);

    /**
     * Find contact methods by user ID.
     *
     * @param userId the user ID
     * @return list of contact methods
     */
    List<ContactMethod> findByUserId(UUID userId);

    /**
     * Find contact method by user ID and type.
     *
     * @param userId the user ID
     * @param type the contact type
     * @return the contact method if found
     */
    Optional<ContactMethod> findByUserIdAndType(UUID userId, ContactType type);

    /**
     * Delete contact method.
     *
     * @param contactMethodId the contact method ID
     * @throws IllegalArgumentException if contact method not found
     */
    void delete(UUID contactMethodId);
}
```

**Step 2: Commit**

```bash
git add src/main/java/org/jwcarman/who/core/service/ContactMethodService.java
git commit -m "feat(core): add ContactMethodService interface

Service for contact method lifecycle:
- Create unverified/verified contact methods
- Mark as verified
- Query by user and type
- Delete contact methods

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

_Due to length, I'll continue in the next comment with JPA entities, implementations, and remaining tasks..._

---

## Task 9-20: JPA Implementation, Service Implementation, REST Controller, Security Auto-Configuration

**Note:** The remaining tasks involve:
- Task 9-10: JPA entities for Invitation and ContactMethod (who-jdbc)
- Task 11-12: Service implementations (DefaultInvitationService, DefaultContactMethodService) (who-core/impl)
- Task 13-14: REST controller and DTOs (InvitationController) (who-web)
- Task 15-16: Security filter chain autoconfiguration (WhoSecurityAutoConfiguration) (who-autoconfigure)
- Task 17-18: Configuration properties updates (WhoProperties) (who-autoconfigure)
- Task 19: Integration tests
- Task 20: Update who-example with InvitationNotifier implementation

These tasks follow the same TDD pattern established above:
1. Write failing test
2. Run to verify failure
3. Implement minimal code
4. Run to verify pass
5. Commit

Each task builds incrementally on the domain foundation created in Tasks 1-8.

---

## Summary

This plan implements the complete invitation system in 20 tasks:

**Foundation (Tasks 1-8):**
- Domain models (Invitation, ContactMethod)
- Enums (InvitationStatus, ContactType)
- Exceptions (6 types)
- Repositories (Invitation, ContactMethod)
- SPIs (InvitationNotifier, ContactVerificationNotifier, ContactConfirmationNotifier)
- Services (InvitationService, ContactMethodService)

**Implementation (Tasks 9-16):**
- JPA persistence layer
- Service implementations with Spring Security integration
- REST API with DTOs
- Security filter chain autoconfiguration

**Integration (Tasks 17-20):**
- Configuration properties
- End-to-end tests
- Example application integration

**Key Principles:**
- Test-Driven Development (TDD) throughout
- Domain-driven design with immutable records
- Clean separation: core → jdbc → security → web → autoconfigure
- Spring-aware services with SecurityContext integration
- Drop-in ready with sensible defaults

