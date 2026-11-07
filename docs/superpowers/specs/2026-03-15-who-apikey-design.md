Status: implemented
Original: specs/done/011-who-apikey.md (Ralph); merges specs/done/012-apikey-name.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

# who-apikey: API key credential module

## What to build

Create the `who-apikey` Maven module. It provides API key generation, storage, and
Spring Security integration. An API key is a prefixed opaque string (`who_<43-char
URL-safe Base64 secret>`) stored as a SHA-256 hash. Identity is always known at creation
time — the key is immediately linked to an existing identity.

### Module setup

New Maven module `who-apikey` with dependencies:
- `who-core`
- `who-spring-security` (for `WhoAuthenticationToken`)
- `spring-boot-starter-web` (for `OncePerRequestFilter`)
- `spring-boot-starter-security`
- `spring-boot-starter-jdbc`
- `who-jdbc`, `spring-boot-starter-test` (test scope)
- `org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter` (test scope)
- `org.postgresql:postgresql` (test scope)

`who-apikey` is in `who-spring-boot-starter` and an optional dependency in
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

`name` is mandatory (spec 012) so users can identify and manage their keys (e.g.
"Production server", "CI pipeline"); there is no default name.

### Repository

**`ApiKeyCredentialRepository`** interface (in `who-apikey`):
```java
Optional<ApiKeyCredential> findByKeyHash(String keyHash);
ApiKeyCredential save(ApiKeyCredential credential);
boolean deleteById(UUID id);   // true if a row was deleted
```

**`JdbcApiKeyCredentialRepository`** — `@Repository` JDBC implementation using `JdbcClient`.
`save()` uses `INSERT ... ON CONFLICT (id) DO UPDATE SET name = :name, key_hash = :keyHash`.

### ApiKeyService

Plain Java class (not a Spring component). Constructor-injected (null-checked) with:
- `ApiKeyCredentialRepository`
- `CredentialIdentityRepository`

**`String create(Identity identity, String name)`** (both required, no overload without `name`):
1. Generate the secret with `Secrets.token()` (32 bytes from `SecureRandom`, URL-safe Base64, no padding)
2. Prepend `who_` → raw key e.g. `who_3q2-7w...`
3. SHA-256 hash the raw key (`MessageDigests.sha256Hex`, 64 hex chars)
4. Save a new `ApiKeyCredential(Identifiers.uuid(), name, keyHash)`
5. Link via `CredentialIdentityRepository.link(credential.id(), identity.id())`
6. Return the raw key — **this is the only time it is available**

**`void revoke(UUID credentialId)`** — `@Transactional`. Deletes the API-key row and, only
if a row was deleted, unlinks that credential id, so another credential's link is never touched.

### ApiKeyAuthenticationFilter

`OncePerRequestFilter`. Constructor-injected (null-checked) with `ApiKeyCredentialRepository`,
`WhoService`, and the header name as a `String` (from properties).

**`doFilterInternal`**:
1. Read the configured header. If it is absent or blank, or the request is already
   authenticated (anything but `null` or an `AnonymousAuthenticationToken`), skip to step 5
2. SHA-256 hash the header value (same algorithm as `ApiKeyService.create`)
3. `apiKeyCredentialRepository.findByKeyHash(hash)` → `whoService.resolve(credential)`
4. If resolved, set `new WhoAuthenticationToken(principal)` in `SecurityContextHolder`
5. Call `filterChain.doFilter()`

On an exception in steps 2–4 the filter clears the `SecurityContextHolder` and throws a
`ServletException`.

### Wiring into a security chain

`WhoApiKeySecurity.apiKey()` returns an `AbstractHttpConfigurer` that adds the
`ApiKeyAuthenticationFilter` bean before `AuthorizationFilter`; `apiKey(filter)` uses a
given instance. Applications apply it to each chain that accepts API keys:
```java
http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
```

The original design had applications call
`http.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`.
0.10.0 replaced that with `WhoApiKeySecurity`, made the filter skip already-authenticated
requests, and stopped the servlet container from running it globally (see
`2026-11-07-security-fixes-design.md`, I1).

### Configuration property

In `WhoProperties` in `who-autoconfigure`:
```
who.api-key.header-name   String, default: "X-API-Key"
```

### Autoconfiguration

In `WhoAutoConfiguration` (`who-autoconfigure`), an `ApiKeyConfiguration` nested class guarded
by `@ConditionalOnClass(name = "org.jwcarman.who.apikey.ApiKeyAuthenticationFilter")`:

```java
@Bean @ConditionalOnMissingBean
ApiKeyCredentialRepository apiKeyCredentialRepository(JdbcClient jdbcClient)

@Bean @ConditionalOnMissingBean
ApiKeyService apiKeyService(ApiKeyCredentialRepository repo,
                            CredentialIdentityRepository credentialIdentityRepository)

@Bean @ConditionalOnMissingBean
ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
        ApiKeyCredentialRepository repo,
        WhoService whoService,
        WhoProperties properties)

@Bean
FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(
        ApiKeyAuthenticationFilter filter)   // setEnabled(false)
```

## Acceptance criteria

- [ ] `who-apikey` module builds successfully
- [ ] Schema SQL exists at `src/main/resources/org/jwcarman/who/apikey/schema.sql`
- [ ] `ApiKeyCredential` record implements `Credential` with `name` field and null-safe compact constructor
- [ ] `ApiKeyCredentialRepository` interface and JDBC implementation exist
- [ ] `ApiKeyService.create(identity, name)` returns a key prefixed with `who_`
- [ ] `ApiKeyService.create()` requires a `name` and persists it
- [ ] `ApiKeyService.create()` stores only the SHA-256 hash — never the raw key
- [ ] `ApiKeyService.create()` links the credential to the identity via `CredentialIdentityRepository`
- [ ] Two calls to `create()` for the same identity produce two different keys, both valid
- [ ] `ApiKeyService.revoke()` removes the key and its identity link, and leaves other credentials' links alone
- [ ] `ApiKeyAuthenticationFilter` resolves a valid key to a `WhoAuthenticationToken` in the `SecurityContext`
- [ ] `ApiKeyAuthenticationFilter` passes through requests with no (or a blank) API key header unmodified
- [ ] `ApiKeyAuthenticationFilter` passes through requests with an unrecognized key unmodified (no 401 — let the chain decide)
- [ ] `ApiKeyAuthenticationFilter` never replaces an existing authentication
- [ ] The filter runs only on chains that apply `WhoApiKeySecurity.apiKey()`
- [ ] Header name is configurable via `who.api-key.header-name` (default `X-API-Key`)
- [ ] `ApiKeyCredentialRepository`, `ApiKeyService`, and `ApiKeyAuthenticationFilter` beans registered by autoconfiguration when `who-apikey` is on the classpath
- [ ] `who-apikey` added to `who-spring-boot-starter`
- [ ] Integration tests pass against real PostgreSQL via Testcontainers
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] Public classes and interfaces have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- SHA-256 comes from `MessageDigests` in `who-core` (`java.security.MessageDigest`) — no third-party crypto dependency
- Raw key is never stored, never logged — only the hash touches the database
- The filter does not produce a 401 on an unrecognized key — it simply does not set authentication,
  leaving the decision to the rest of the filter chain. (Unlike `WhoJwtAuthenticationConverter`, which
  rejects an unknown JWT credential with a 401.)
- No salt needed — the 256-bit secret has sufficient entropy to make rainbow tables impractical
- Constructor injection only throughout
- No Spring annotations in `who-core`; `ApiKeyService` is plain Java apart from `@Transactional` on `revoke`
