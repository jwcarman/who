# who-jwt: JWT credential extractor and Spring Security wiring

## What to build

Create the `who-jwt` Maven module. It provides the `JwtCredential` type, its JDBC
repository, database schema, and the Spring Security `JwtAuthenticationConverter`
that bridges an already-validated Spring Security `Jwt` into a `WhoPrincipal` via
`WhoService.resolve()`.

This module does NOT validate JWTs — Spring Security's OAuth2 resource server handles
that. This module kicks in after validation to resolve the identity.

### Module setup

New Maven module `who-jwt` with dependencies:
- `who-core`
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-test` (test scope)
- `org.testcontainers:postgresql` (test scope)
- `org.postgresql:postgresql` (test scope)

### Schema

Resource file at `src/main/resources/org/jwcarman/who/jwt/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS who_jwt_credential (
    id      UUID         PRIMARY KEY,
    issuer  VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject)
);
```

### JwtCredential

Implements `Credential`. Immutable record:
```
id       UUID   (the credential UUID — satisfies Credential.id())
issuer   String
subject  String
```

Static factory: `JwtCredential.create(String issuer, String subject)` — generates a random UUID.

### JwtCredentialRepository

Interface:
```java
Optional<JwtCredential> findByIssuerAndSubject(String issuer, String subject);
JwtCredential save(JwtCredential credential);
void deleteById(UUID id);
```

JDBC implementation using `JdbcClient` and `@Repository`. `save()` uses:
```sql
INSERT INTO who_jwt_credential (id, issuer, subject)
VALUES (:id, :issuer, :subject)
ON CONFLICT (id) DO NOTHING
```
(JWT credentials are immutable after creation — issuer/subject never change.)

### WhoJwtAuthenticationConverter

Implements `Converter<Jwt, AbstractAuthenticationToken>`. Constructor-injected with
`JwtCredentialRepository` and `WhoService`.

Logic:
1. Extract `iss` and `sub` claims from the `Jwt`
2. Look up `JwtCredentialRepository.findByIssuerAndSubject(iss, sub)`
3. If not found → return `null` (Spring Security will reject the request as unauthenticated)
4. Call `WhoService.resolve(jwtCredential)`
5. If empty → return `null`
6. Return `WhoAuthenticationToken(whoPrincipal, authorities)`

### WhoAuthenticationToken

`extends AbstractAuthenticationToken`. Constructor-injected with `WhoPrincipal` and
`Collection<GrantedAuthority>`. Marked authenticated on construction.

- `getPrincipal()` → the `WhoPrincipal`
- `getCredentials()` → `null`

Permissions from `WhoPrincipal.permissions()` are converted to `SimpleGrantedAuthority`
instances for the authorities collection.

## Acceptance criteria

- [ ] `who-jwt` module exists and builds successfully
- [ ] `JwtCredential` implements `Credential` and is an immutable record
- [ ] `JwtCredentialRepository` interface and JDBC implementation exist
- [ ] Schema exists at `src/main/resources/org/jwcarman/who/jwt/schema.sql`
- [ ] `WhoJwtAuthenticationConverter.convert()` returns `null` when no JWT credential is found
- [ ] `WhoJwtAuthenticationConverter.convert()` returns `null` when `WhoService.resolve()` returns empty
- [ ] `WhoJwtAuthenticationConverter.convert()` returns a `WhoAuthenticationToken` with the correct `WhoPrincipal` for a valid credential
- [ ] `WhoAuthenticationToken.getPrincipal()` returns the `WhoPrincipal`
- [ ] `WhoAuthenticationToken.getCredentials()` returns `null`
- [ ] Authorities on `WhoAuthenticationToken` match the permissions in `WhoPrincipal`
- [ ] `WhoJwtAuthenticationConverter` is tested with mocked `JwtCredentialRepository` and `WhoService`
- [ ] JDBC integration tests cover find, save, and conflict behavior against a real PostgreSQL instance via Testcontainers
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes
- [ ] Public classes and interfaces have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- This module does NOT auto-provision identities — if the JWT credential is not found, access is denied. Auto-provisioning (creating a `JwtCredential` on first login) would be a future feature
- Constructor injection only throughout
- `WhoService` is a plain Java class from `who-core` — it will be injected as a Spring bean via `who-autoconfigure`; this module just uses it
- The `iss` claim in Spring Security's `Jwt` is available via `jwt.getIssuer().toString()` or `jwt.getClaimAsString("iss")` — prefer `getIssuer()` as it is typed
- `WhoAuthenticationToken` lives in `who-jwt` for now; it can be extracted to a shared module if `who-apikey` needs it later
