Status: implemented
Original: specs/done/004-who-jwt.md (Ralph); merges specs/done/009-refactor-jwt-converter.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

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
- `who-spring-security` (for `WhoAuthenticationToken`)
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-jdbc`
- `spring-boot-starter-test` (test scope)
- `org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter` (test scope)
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

Static factory: `JwtCredential.create(String issuer, String subject)` — id from
`Identifiers.uuid()` (UUIDv7).

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

Implements `Converter<Jwt, AbstractAuthenticationToken>`. Constructor-injected (null-checked)
with `JwtCredentialRepository` and `WhoService`.

Logic:
1. Read the `iss` and `sub` claims with `jwt.getClaimAsString(...)` (so a non-URL issuer works)
2. If either is missing or blank → throw `BadCredentialsException` (401)
3. Look up `JwtCredentialRepository.findByIssuerAndSubject(iss, sub)`
4. Call `WhoService.resolve(jwtCredential)`
5. If no credential or no principal → throw `BadCredentialsException("Unknown JWT credential")` (401)
6. Return `new WhoAuthenticationToken(whoPrincipal)`

Steps 3–6 are one Optional chain (spec 009):
```java
return jwtCredentialRepository
    .findByIssuerAndSubject(issuer, subject)
    .flatMap(whoService::resolve)
    .map(WhoAuthenticationToken::new)
    .orElseThrow(() -> new BadCredentialsException("Unknown JWT credential"));
```

The original design returned `null` for an unknown credential and read the issuer with
`getIssuer()`; 0.10.0 changed both so failures are a 401, never a 500 (see
`2026-11-07-security-fixes-design.md`, I3).

### WhoAuthenticationToken

Lives in `who-spring-security` (`org.jwcarman.who.spring.security`), shared with `who-apikey`;
see `2026-03-15-who-spring-security-design.md` and
`2026-03-15-authentication-token-principal-only-design.md`. It takes only the `WhoPrincipal`,
derives `SimpleGrantedAuthority` instances from its permissions, is authenticated on
construction, and returns the `WhoPrincipal` from `getPrincipal()` and `null` from
`getCredentials()`.

## Acceptance criteria

- [ ] `who-jwt` module exists and builds successfully
- [ ] `JwtCredential` implements `Credential` and is an immutable record
- [ ] `JwtCredentialRepository` interface and JDBC implementation exist
- [ ] Schema exists at `src/main/resources/org/jwcarman/who/jwt/schema.sql`
- [ ] `WhoJwtAuthenticationConverter.convert()` throws `BadCredentialsException` when `iss` or `sub` is missing or blank
- [ ] `WhoJwtAuthenticationConverter.convert()` throws `BadCredentialsException` when no JWT credential is found
- [ ] `WhoJwtAuthenticationConverter.convert()` throws `BadCredentialsException` when `WhoService.resolve()` returns empty
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

- This module does NOT auto-provision identities — if the JWT credential is not found, access is denied (401). Linking a JWT credential to an identity is done through `who-enrollment`
- Constructor injection only throughout
- `WhoService` is a plain Java class from `who-core` — it is injected as a Spring bean via `who-autoconfigure`; this module just uses it
- JWT validation, including issuer validation, is the resource server's job: configure `issuer-uri`, or a `JwtIssuerValidator` with `jwk-set-uri`
