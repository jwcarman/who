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

### Clean up who-core

Strip `who-core` back to a clean module with no existing implementation — start fresh.
The `who-core` `pom.xml` must have **no Spring dependency**. Pure Java only.

### Domain types

**`IdentityStatus`** — enum: `ACTIVE`, `SUSPENDED`, `DISABLED`

**`Identity`** — immutable record:
```
id         UUID
status     IdentityStatus
createdAt  Instant
updatedAt  Instant
```
Include a static `create(UUID id, IdentityStatus status)` factory method that sets
both timestamps to `Instant.now()`.
Include a `withStatus(IdentityStatus newStatus)` method that returns a new `Identity`
with an updated `updatedAt`.

**`WhoPrincipal`** — immutable record:
```
identityId   UUID
permissions  Set<String>
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

Plain Java class (no Spring annotations). Constructor-injected with:
- `IdentityRepository`
- `CredentialIdentityRepository`
- `List<PermissionsResolver>` (all registered resolvers; permissions are unioned across all)

**Method:**
```java
Optional<WhoPrincipal> resolve(Credential credential)
```

Logic:
1. Look up `credentialId` → `identityId` via `CredentialIdentityRepository`
2. Return `Optional.empty()` if not found
3. Load `Identity` from `IdentityRepository`
4. Return `Optional.empty()` if identity not found or status is not `ACTIVE`
5. Call all `PermissionsResolver` instances, union the results
6. Return `Optional.of(new WhoPrincipal(identityId, permissions))`

## Acceptance criteria

- [ ] All modules except `who-core` are deleted and removed from the root `pom.xml`
- [ ] `who-core/pom.xml` has no Spring dependency — only pure Java (JUnit/Mockito for tests)
- [ ] `Identity`, `IdentityStatus`, `WhoPrincipal` records exist with null-safe compact constructors
- [ ] `Credential` and `PermissionsResolver` interfaces exist in `who-core`
- [ ] `IdentityRepository` and `CredentialIdentityRepository` interfaces exist in `who-core`
- [ ] `WhoService.resolve(Credential)` returns `Optional.empty()` when credential is not linked to any identity
- [ ] `WhoService.resolve(Credential)` returns `Optional.empty()` when the resolved identity is not `ACTIVE`
- [ ] `WhoService.resolve(Credential)` unions permissions from all registered `PermissionsResolver` instances
- [ ] `WhoService.resolve(Credential)` returns a populated `WhoPrincipal` for a valid, active identity
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes (all new Java files have Apache 2.0 headers)
- [ ] Public interfaces and `WhoService` have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- No `@Service`, `@Component`, `@Autowired`, or any Spring annotation anywhere in `who-core`
- Constructor injection only — `WhoService` takes its dependencies via constructor
- `WhoService` is a plain Java class; Spring wiring will be added in a future `who-autoconfigure` module
- `PermissionsResolver` returning an empty set is valid — not an error
- If `List<PermissionsResolver>` is empty, `WhoPrincipal` gets an empty permissions set
- The `SUSPENDED` and `DISABLED` statuses both result in `Optional.empty()` from `resolve()` — suspended/disabled identities cannot authenticate
- All domain records should use `requireNonNull` in their compact constructors
