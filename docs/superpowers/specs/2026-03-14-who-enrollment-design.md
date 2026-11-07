Status: implemented
Original: specs/done/007-who-enrollment.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

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
- `who-jdbc` (test scope — the schema references `who_identity`)
- `spring-boot-starter-test` (test scope)
- `org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter` (test scope)
- `org.postgresql:postgresql` (test scope)

### Schema

Resource file at `src/main/resources/org/jwcarman/who/enrollment/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS who_enrollment_token (
    id          UUID         PRIMARY KEY,
    identity_id UUID         NOT NULL,
    token_value VARCHAR(255) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP(9) NOT NULL,
    expires_at  TIMESTAMP(9) NOT NULL,
    redeemed_at TIMESTAMP(9),
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);
```

`token_value` holds the SHA-256 hex hash of the token, never the raw value.

### Domain types

**`EnrollmentTokenStatus`** — enum: `PENDING`, `REDEEMED`, `REVOKED`

**`EnrollmentToken`** — immutable record:
```
id          UUID
identityId  UUID
value       String   (the raw token on the instance returned by createToken; the hash when loaded)
status      EnrollmentTokenStatus
createdAt   Instant
expiresAt   Instant
redeemedAt  Instant  (nullable)
```

Include:
- `isExpired()` — returns true if `Instant.now().isAfter(expiresAt)`
- `isPending()` — returns true if `status == PENDING && !isExpired()`
- `redeem()` — returns new token with status `REDEEMED` and `redeemedAt = now`
- `revoke()` — returns new token with status `REVOKED` (keeps `redeemedAt`)
- `toString()` masks the value (`value=***`)

Static factory `EnrollmentToken.create(Identity identity, Duration expiration)` — id from
`Identifiers.uuid()`, value from `Secrets.token()` (256 bits from `SecureRandom`), status
`PENDING`, `createdAt` now, `expiresAt = now + expiration`.

**Exceptions** (all extend `WhoException`): `EnrollmentTokenNotFoundException` (message does
not include the submitted value), `EnrollmentTokenExpiredException`,
`EnrollmentTokenNotPendingException`, `EnrollmentIdentityInactiveException`,
`CredentialAlreadyLinkedException`.

### Repository interface

**`EnrollmentTokenRepository`**:
```java
EnrollmentToken save(EnrollmentToken token);
Optional<EnrollmentToken> findById(UUID id);
Optional<EnrollmentToken> findByValue(String value);      // by stored hash
void revokeAllPendingForIdentity(UUID identityId);
boolean redeem(UUID id, Instant now);  // PENDING and unexpired → REDEEMED; true if a row changed
boolean revoke(UUID id);               // PENDING → REVOKED; true if a row changed
void deleteById(UUID id);
```

JDBC implementation using `JdbcClient` and `@Repository`. `save()` uses
`INSERT ... ON CONFLICT (id) DO UPDATE SET status = :status, redeemed_at = :redeemedAt`.
`redeem()` is a single conditional update:
`UPDATE ... SET status = 'REDEEMED', redeemed_at = :now WHERE id = :id AND status = 'PENDING' AND expires_at > :now`.

### WhoEnrollmentService

Plain Java class (not a Spring component; only `@Transactional` annotations). Constructor-injected with:
- `EnrollmentTokenRepository`
- `IdentityRepository`
- `CredentialIdentityRepository`
- `Duration tokenExpiration` (from configuration)

**Methods:**

```java
EnrollmentToken createToken(Identity identity)
```
- Validates the identity exists — throws `IllegalArgumentException` if not
- Revokes the identity's other `PENDING` tokens
- Creates a new `EnrollmentToken` and saves it with its value replaced by the SHA-256 hash
- Returns the token with the raw value — caller is responsible for delivering `token.value()`
  to the user; it cannot be recovered later

```java
Identity enroll(String tokenValue, Credential credential)
```
Must be `@Transactional`.
- Finds token by the hash of `tokenValue` — throws `EnrollmentTokenNotFoundException` if not found
- Loads the identity; throws `EnrollmentIdentityInactiveException` if it is not `ACTIVE`
  (nothing redeemed or linked)
- Redeems atomically with `redeem(id, now)`; if that fails, rereads the token and throws
  `EnrollmentTokenExpiredException` (expired) or `EnrollmentTokenNotPendingException`
- Only then links the credential to the identity via `CredentialIdentityRepository.link()`;
  a credential already linked throws `CredentialAlreadyLinkedException`
- Returns the `Identity`

```java
void revokeToken(EnrollmentToken token)
```
`@Transactional`. Throws `EnrollmentTokenNotFoundException` if the token does not exist,
otherwise revokes it (only a `PENDING` token changes).

```java
Optional<EnrollmentToken> findToken(String tokenValue)
```
- Looks up by the hash of the value — no side effects

The atomic redemption, the active-identity check, `CredentialAlreadyLinkedException`, the
`SecureRandom` values and the masked messages are from 0.10.0; see
`2026-11-07-security-fixes-design.md` (C1, C2, I4, I6).

### Configuration property

In `WhoProperties` in `who-autoconfigure`:
```
who.enrollment.token-expiration   Duration, default: 24h
```

### Autoconfiguration

In `WhoAutoConfiguration` (`who-autoconfigure`), an `EnrollmentConfiguration` nested class
guarded by `@ConditionalOnClass(name = "org.jwcarman.who.enrollment.WhoEnrollmentService")`:
```java
@Bean @ConditionalOnMissingBean
EnrollmentTokenRepository enrollmentTokenRepository(JdbcClient jdbcClient)

@Bean @ConditionalOnMissingBean
WhoEnrollmentService whoEnrollmentService(
        EnrollmentTokenRepository enrollmentTokenRepository,
        IdentityRepository identityRepository,
        CredentialIdentityRepository credentialIdentityRepository,
        WhoProperties properties)
```

`who-enrollment` is an optional dependency in `who-autoconfigure/pom.xml` and is included in
`who-spring-boot-starter/pom.xml`.

## Acceptance criteria

- [ ] `who-enrollment` module exists and builds successfully
- [ ] Schema SQL exists at `src/main/resources/org/jwcarman/who/enrollment/schema.sql`
- [ ] `EnrollmentToken` record exists with null-safe compact constructor (`redeemedAt` nullable), `isExpired()`, `isPending()`, `redeem()`, `revoke()`
- [ ] `EnrollmentTokenRepository` interface and JDBC implementation exist
- [ ] Only the SHA-256 hash of a token value is stored
- [ ] `WhoEnrollmentService.createToken()` throws `IllegalArgumentException` for unknown identity
- [ ] `WhoEnrollmentService.createToken()` returns a token with `status = PENDING` and revokes the identity's earlier pending tokens
- [ ] `WhoEnrollmentService.enroll()` links the credential to the identity and marks the token `REDEEMED`
- [ ] `WhoEnrollmentService.enroll()` throws for unknown token, expired token, already-redeemed token, inactive identity, and an already-linked credential
- [ ] `WhoEnrollmentService.enroll()` is `@Transactional` and redeems atomically (two concurrent calls cannot both succeed)
- [ ] `WhoEnrollmentService.revokeToken()` marks the token `REVOKED`
- [ ] A redeemed enrollment token cannot be redeemed again
- [ ] A revoked enrollment token cannot be redeemed
- [ ] Deleting an identity cascades to delete its enrollment tokens
- [ ] `who.enrollment.token-expiration` property is respected (default 24h)
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
  calling `enroll()` — the enrollment service only handles the linking. The credential must
  come from the redeemer's own authentication (for JWT, the validated token's issuer and
  subject), never from the request body (documented in `docs/guides/enrollment.md`)
- An enrollment token is single-use — once redeemed it cannot be used again, even if
  the credential is later unlinked
- `EXPIRED` is not a status stored in the database — expiry is determined at runtime by
  comparing `expires_at` to the current time. The status column only tracks explicit
  state transitions (PENDING → REDEEMED or PENDING → REVOKED)
- Constructor injection only throughout
- An identity has at most one outstanding `PENDING` token: `createToken()` revokes the
  earlier ones. (The original design allowed several.)
