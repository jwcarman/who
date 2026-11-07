Status: implemented
Original: specs/done/001-who-core-foundation.md (Ralph); merges specs/done/008-refactor-whoservice.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

# who-core foundation: Identity, Credential, WhoService

## What to build

Gut the existing codebase and establish the `who-core` module as a pure Java foundation
with zero Spring dependency. All other existing modules (`who-jdbc`, `who-security`,
`who-web`, `who-autoconfigure`, `who-spring-boot-starter`, `who-example`) are to be
deleted and removed from the parent POM.

### Remove existing modules

Delete the following module directories entirely and remove them from the `<modules>`
section of the root `pom.xml`:

- `who-jdbc`
- `who-security`
- `who-web`
- `who-autoconfigure`
- `who-spring-boot-starter`
- `who-example`

(One-time migration step. Several of these names were later rebuilt from scratch with a
different design; see their own specs.)

### Clean up who-core

Strip `who-core` back to a clean module with no existing implementation — start fresh.
The `who-core` `pom.xml` must have **no Spring dependency**. Its only runtime dependency is
`com.fasterxml.uuid:java-uuid-generator` (UUIDv7 ids); `spring-boot-starter-test` is
test scope only.

### Domain types

**`IdentityStatus`** — enum: `ACTIVE`, `SUSPENDED`, `DISABLED`

**`Identity`** — immutable record:
```
id         UUID
status     IdentityStatus
createdAt  Instant
updatedAt  Instant
```
Static factories `create()` (an `ACTIVE` identity) and `create(IdentityStatus status)`:
the id comes from `Identifiers.uuid()` (UUIDv7) and both timestamps are set to `Instant.now()`.
Include a `withStatus(IdentityStatus newStatus)` method that returns a new `Identity`
with an updated `updatedAt`.

**`WhoPrincipal`** — immutable record, `Serializable`:
```
identity     Identity
permissions  Set<String>   (defensively copied with Set.copyOf)
```

### SPIs

**`Credential`** — interface:
```java
UUID id();
```

**`PermissionsResolver`** — interface:
```java
Set<String> resolve(Identity identity);
```

### Repository interfaces

**`IdentityRepository`**:
```java
Optional<Identity> findById(UUID id);
Identity save(Identity identity);
boolean existsById(UUID id);
void deleteById(UUID id);
```

**`CredentialIdentityRepository`**:
```java
// Maps a credential UUID to an identity UUID
Optional<UUID> findIdentityIdByCredentialId(UUID credentialId);

// Links a credential to an identity
void link(UUID credentialId, UUID identityId);

// Unlinks a credential from its identity
void unlink(UUID credentialId);
```

### WhoService

Plain Java class (no Spring annotations). Constructor-injected (all three null-checked) with:
- `IdentityRepository`
- `CredentialIdentityRepository`
- `PermissionsResolver` — a single resolver (decision from spec 008: supporting multiple
  resolvers was speculative complexity; anyone who needs to combine sources implements a
  composite resolver)

**Methods:**
```java
Identity createIdentity()
```
Saves and returns a new `ACTIVE` identity (`Identity.create()`). This is the entry point
applications use to create identities.

```java
Optional<WhoPrincipal> resolve(Credential credential)
```

Implemented as an Optional chain (spec 008):
```java
return credentialIdentityRepository.findIdentityIdByCredentialId(credential.id())
    .flatMap(identityRepository::findById)
    .filter(identity -> identity.status() == IdentityStatus.ACTIVE)
    .map(identity -> new WhoPrincipal(identity, permissionsResolver.resolve(identity)));
```

Logic:
1. Look up `credentialId` → `identityId` via `CredentialIdentityRepository`
2. Return `Optional.empty()` if not found
3. Load `Identity` from `IdentityRepository`
4. Return `Optional.empty()` if identity not found or status is not `ACTIVE`
5. Call the `PermissionsResolver`
6. Return `Optional.of(new WhoPrincipal(identity, permissions))`

### Shared utilities (added later)

`who-core` also holds small helpers used by the other modules: `Identifiers.uuid()`
(UUIDv7; ids are not secrets), `WhoException` (base runtime exception with a format
pattern), `MessageDigests.sha256Hex`, and `Secrets.token()` (256 bits from `SecureRandom`,
URL-safe Base64). `Secrets` came from the 0.10.0 security fixes; see
`2026-11-07-security-fixes-design.md`.

## Acceptance criteria

- [ ] All modules except `who-core` are deleted and removed from the root `pom.xml`
- [ ] `who-core/pom.xml` has no Spring dependency — only pure Java (plus `java-uuid-generator`; test-scope test libraries)
- [ ] `Identity`, `IdentityStatus`, `WhoPrincipal` records exist with null-safe compact constructors
- [ ] `Credential` and `PermissionsResolver` interfaces exist in `who-core`
- [ ] `IdentityRepository` and `CredentialIdentityRepository` interfaces exist in `who-core`
- [ ] `WhoService.resolve(Credential)` returns `Optional.empty()` when credential is not linked to any identity
- [ ] `WhoService.resolve(Credential)` returns `Optional.empty()` when the resolved identity is not `ACTIVE`
- [ ] `WhoService.resolve(Credential)` returns the permissions from the `PermissionsResolver`
- [ ] `WhoService.resolve(Credential)` returns a populated `WhoPrincipal` for a valid, active identity
- [ ] `WhoService.createIdentity()` persists and returns an `ACTIVE` identity
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes (all new Java files have Apache 2.0 headers)
- [ ] Public interfaces and `WhoService` have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- No `@Service`, `@Component`, `@Autowired`, or any Spring annotation anywhere in `who-core`
- Constructor injection only — `WhoService` takes its dependencies via constructor
- `WhoService` is a plain Java class; Spring wiring is in `who-autoconfigure`, which supplies
  a default `PermissionsResolver` returning an empty set when the application has none
- `PermissionsResolver` returning an empty set is valid — not an error
- The `SUSPENDED` and `DISABLED` statuses both result in `Optional.empty()` from `resolve()` — suspended/disabled identities cannot authenticate
- All domain records should use `requireNonNull` in their compact constructors
