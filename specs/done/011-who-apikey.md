# who-apikey: API key credential module

## What to build

Create the `who-apikey` Maven module. It provides API key generation, storage, and
Spring Security integration. An API key is a prefixed opaque string (`who_<64 hex chars>`)
stored as a SHA-256 hash. Identity is always known at creation time — the key is
immediately linked to an existing identity.

### Module setup

New Maven module `who-apikey` with dependencies:
- `who-core`
- `spring-boot-starter-web` (for `OncePerRequestFilter`)
- `spring-boot-starter-jdbc`
- `spring-boot-starter-test` (test scope)
- `org.testcontainers:postgresql` (test scope)
- `org.postgresql:postgresql` (test scope)

Add `who-apikey` to `who-spring-boot-starter` and as an optional dependency in
`who-autoconfigure/pom.xml`.

### Schema

Resource file at `src/main/resources/org/jwcarman/who/apikey/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id       UUID         PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    key_hash VARCHAR(64)  NOT NULL UNIQUE
);
```

### Domain type

**`ApiKeyCredential(UUID id, String name, String keyHash) implements Credential`** — immutable record.
Compact constructor must null-check all fields.

### Repository

**`ApiKeyCredentialRepository`** interface (in `who-apikey`):
```java
Optional<ApiKeyCredential> findByKeyHash(String keyHash);
ApiKeyCredential save(ApiKeyCredential credential);
void deleteById(UUID id);
```

**`JdbcApiKeyCredentialRepository`** — JDBC implementation using `JdbcClient`.
`save()` uses `INSERT ... ON CONFLICT (id) DO UPDATE SET key_hash = :keyHash`.

### ApiKeyService

Plain Java class (no Spring annotations). Constructor-injected with:
- `ApiKeyCredentialRepository`
- `CredentialIdentityRepository`

**`String create(UUID identityId, String name)`**:
1. Generate 32 random bytes via `SecureRandom`
2. Hex-encode them → 64 hex chars
3. Prepend `who_` → raw key e.g. `who_a3f8...`
4. SHA-256 hash the raw key (`MessageDigest.getInstance("SHA-256")`, hex-encode result)
5. Save a new `ApiKeyCredential(UUID.randomUUID(), name, keyHash)`
6. Link via `CredentialIdentityRepository.link(credential.id(), identityId)`
7. Return the raw key — **this is the only time it is available**

### ApiKeyAuthenticationFilter

`OncePerRequestFilter`. Constructor-injected with `ApiKeyCredentialRepository` and
`WhoService`. Header name injected as a `String` (from properties).

**`doFilterInternal`**:
1. Read the configured header from the request — if absent or blank, call
   `filterChain.doFilter()` and return (let the request proceed unauthenticated)
2. SHA-256 hash the header value (same algorithm as `ApiKeyService.create`)
3. Call `apiKeyCredentialRepository.findByKeyHash(hash)`
4. If found, call `whoService.resolve(credential)`
5. If resolved, build a `WhoAuthenticationToken` and set it in `SecurityContextHolder`
6. Call `filterChain.doFilter()`

The filter must clear the `SecurityContextHolder` on exception.

### Configuration property

Add to `WhoProperties` in `who-autoconfigure`:
```
who.api-key.header-name   String, default: "X-API-Key"
```

### Autoconfiguration

Add to `WhoAutoConfiguration` in `who-autoconfigure` under a new
`@ConditionalOnClass(name = "org.jwcarman.who.apikey.ApiKeyAuthenticationFilter")`
inner configuration class:

```java
@Bean
@ConditionalOnMissingBean
ApiKeyCredentialRepository apiKeyCredentialRepository(JdbcClient jdbcClient)

@Bean
@ConditionalOnMissingBean
ApiKeyService apiKeyService(ApiKeyCredentialRepository repo,
                            CredentialIdentityRepository credentialIdentityRepository)

@Bean
@ConditionalOnMissingBean
ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
        ApiKeyCredentialRepository repo,
        WhoService whoService,
        WhoProperties properties)
```

The application adds the filter to its security filter chain via
`http.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`.

## Acceptance criteria

- [ ] `who-apikey` module builds successfully
- [ ] Schema SQL exists at `src/main/resources/org/jwcarman/who/apikey/schema.sql`
- [ ] `ApiKeyCredential` record implements `Credential` with `name` field and null-safe compact constructor
- [ ] `ApiKeyCredentialRepository` interface and JDBC implementation exist
- [ ] `ApiKeyService.create(identityId, name)` returns a key prefixed with `who_`
- [ ] `ApiKeyService.create()` stores only the SHA-256 hash — never the raw key
- [ ] `ApiKeyService.create()` links the credential to the identity via `CredentialIdentityRepository`
- [ ] Two calls to `create()` for the same identity produce two different keys, both valid
- [ ] `ApiKeyAuthenticationFilter` resolves a valid key to a `WhoAuthenticationToken` in the `SecurityContext`
- [ ] `ApiKeyAuthenticationFilter` passes through requests with no API key header unmodified
- [ ] `ApiKeyAuthenticationFilter` passes through requests with an unrecognized key unmodified (no 401 — let the chain decide)
- [ ] Header name is configurable via `who.api-key.header-name` (default `X-API-Key`)
- [ ] `ApiKeyCredentialRepository`, `ApiKeyService`, and `ApiKeyAuthenticationFilter` beans registered by autoconfiguration when `who-apikey` is on the classpath
- [ ] `who-apikey` added to `who-spring-boot-starter`
- [ ] Integration tests pass against real PostgreSQL via Testcontainers
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] Public classes and interfaces have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- Use `java.security.MessageDigest` for SHA-256 — no third-party crypto dependency needed
- Raw key is never stored, never logged — only the hash touches the database
- The filter does not produce a 401 on an unrecognized key — it simply does not set authentication,
  leaving the decision to the rest of the filter chain (consistent with how `WhoJwtAuthenticationConverter`
  returns `null` for unrecognized credentials)
- No salt needed — the `who_<64 hex chars>` key has sufficient entropy to make rainbow tables
  impractical
- Constructor injection only throughout
- No Spring annotations in `who-core` — `ApiKeyService` is in `who-apikey` and may use Spring if needed,
  but prefer plain Java
