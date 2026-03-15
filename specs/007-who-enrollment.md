# who-enrollment: enrollment token service

## What to build

Create the `who-enrollment` Maven module. It provides a service for generating
one-time enrollment tokens that link a `Credential` to a pre-existing `Identity`.
The library generates and validates tokens — the application decides when to create
them, how to deliver them, and what the redemption UX looks like.

### Module setup

New Maven module `who-enrollment` with dependencies:
- `who-core`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-test` (test scope)
- `org.testcontainers:postgresql` (test scope)
- `org.postgresql:postgresql` (test scope)

### Schema

Resource file at `src/main/resources/org/jwcarman/who/enrollment/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS who_enrollment_token (
    id          UUID         PRIMARY KEY,
    identity_id UUID         NOT NULL,
    value       VARCHAR(255) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP(9) NOT NULL,
    expires_at  TIMESTAMP(9) NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);
```

### Domain types

**`EnrollmentTokenStatus`** — enum: `PENDING`, `REDEEMED`, `REVOKED`

**`EnrollmentToken`** — immutable record:
```
id          UUID
identityId  UUID
value       String   (random UUID as string — this is what gets shared with the user)
status      EnrollmentTokenStatus
createdAt   Instant
expiresAt   Instant
```

Include:
- `isExpired()` — returns true if `Instant.now().isAfter(expiresAt)`
- `isPending()` — returns true if `status == PENDING && !isExpired()`
- `redeem()` — returns new token with status `REDEEMED`
- `revoke()` — returns new token with status `REVOKED`

Static factory `EnrollmentToken.create(UUID identityId, int expirationHours)` — generates
random UUIDs for `id` and `value`, sets status to `PENDING`, timestamps to now.

### Repository interface

**`EnrollmentTokenRepository`**:
```java
EnrollmentToken save(EnrollmentToken token);
Optional<EnrollmentToken> findById(UUID id);
Optional<EnrollmentToken> findByValue(String value);
void deleteById(UUID id);
```

JDBC implementation using `JdbcClient` and `@Repository`. `save()` uses
`INSERT ... ON CONFLICT (id) DO UPDATE SET status = :status`.

### WhoEnrollmentService

Plain Java class (no Spring annotations). Constructor-injected with:
- `EnrollmentTokenRepository`
- `IdentityRepository`
- `CredentialIdentityRepository`
- `int expirationHours` (from configuration)

**Methods:**

```java
EnrollmentToken createToken(UUID identityId)
```
- Validates the identity exists — throws `IllegalArgumentException` if not
- Creates and saves a new `EnrollmentToken`
- Returns the saved token — caller is responsible for delivering `token.value()` to the user

```java
Identity enroll(String tokenValue, Credential credential)
```
- Finds token by value — throws if not found
- Validates token is `PENDING` and not expired — throws specific exception for each case
- Links the credential to the identity via `CredentialIdentityRepository.link()`
- Marks the token as `REDEEMED` and saves it
- Loads and returns the `Identity`
- Must be `@Transactional`

```java
void revokeToken(UUID tokenId)
```
- Finds token by id — throws if not found
- Marks as `REVOKED` and saves

```java
Optional<EnrollmentToken> findToken(String tokenValue)
```
- Looks up by value — no side effects

### Configuration property

Add to `WhoProperties` in `who-autoconfigure`:
```
who.enrollment.expiration-hours   int, default: 24
```

### Autoconfiguration

Add to `WhoAutoConfiguration` in `who-autoconfigure`:
```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnClass(WhoEnrollmentService.class)
WhoEnrollmentService whoEnrollmentService(
        EnrollmentTokenRepository enrollmentTokenRepository,
        IdentityRepository identityRepository,
        CredentialIdentityRepository credentialIdentityRepository,
        WhoProperties properties)
```

Also add `who-enrollment` as an optional dependency in `who-autoconfigure/pom.xml`
and add it to `who-spring-boot-starter/pom.xml`.

## Acceptance criteria

- [ ] `who-enrollment` module exists and builds successfully
- [ ] Schema SQL exists at `src/main/resources/org/jwcarman/who/enrollment/schema.sql`
- [ ] `EnrollmentToken` record exists with null-safe compact constructor, `isExpired()`, `isPending()`, `redeem()`, `revoke()`
- [ ] `EnrollmentTokenRepository` interface and JDBC implementation exist
- [ ] `WhoEnrollmentService.createToken()` throws `IllegalArgumentException` for unknown identity
- [ ] `WhoEnrollmentService.createToken()` returns a token with `status = PENDING`
- [ ] `WhoEnrollmentService.enroll()` links the credential to the identity and marks the token `REDEEMED`
- [ ] `WhoEnrollmentService.enroll()` throws for unknown token, expired token, and already-redeemed token
- [ ] `WhoEnrollmentService.enroll()` is `@Transactional`
- [ ] `WhoEnrollmentService.revokeToken()` marks the token `REVOKED`
- [ ] A redeemed enrollment token cannot be redeemed again
- [ ] A revoked enrollment token cannot be redeemed
- [ ] Deleting an identity cascades to delete its enrollment tokens
- [ ] `who.enrollment.expiration-hours` property is respected (default 24)
- [ ] `WhoEnrollmentService` bean is registered by autoconfiguration when `who-enrollment` is on the classpath
- [ ] `who-enrollment` added to `who-spring-boot-starter` dependencies
- [ ] Integration tests pass against a real PostgreSQL instance via Testcontainers
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes
- [ ] Public classes and interfaces have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- The library does not deliver the token — the application calls `createToken()` and
  does whatever it wants with `token.value()` (email it, show it in the UI, etc.)
- The application is responsible for creating the credential (e.g. `JwtCredential`) before
  calling `enroll()` — the enrollment service only handles the linking
- An enrollment token is single-use — once redeemed it cannot be used again, even if
  the credential is later unlinked
- `EXPIRED` is not a status stored in the database — expiry is determined at runtime by
  comparing `expires_at` to `Instant.now()`. The status column only tracks explicit
  state transitions (PENDING → REDEEMED or PENDING → REVOKED)
- Constructor injection only throughout
- An identity can have multiple outstanding PENDING enrollment tokens — useful for
  issuing tokens for multiple credential types simultaneously
