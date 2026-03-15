# PRD — Who

---

## What this project is

A reusable Spring Boot library that solves identity resolution for API applications. It maps
external credentials (JWT iss/sub pairs, API keys, etc.) to a stable internal `Identity` UUID
that application domain objects can FK against, and provides RBAC (roles → permission strings)
so the application knows what that identity is allowed to do. Eliminates the boilerplate of
writing credential extraction, identity mapping, and permission loading from scratch on every
project.

The core pipeline on every request is:

```
HttpRequest → CredentialExtractor → Credential → Identity → Set<String> permissions → WhoPrincipal
```

The `WhoPrincipal` ends up in the Spring Security context. The application never needs to know
whether the caller used a JWT or an API key — the principal shape is always the same.

---

## Tech stack

- Language: Java 25
- Framework: Spring Boot 4.0.2
- Build: Maven 3.8+ (no wrapper — use `mvn`)
- Testing: JUnit 5 + Mockito (via `spring-boot-starter-test`), H2 in-memory for JDBC tests
- License headers: mycila license-maven-plugin (enforced via `-P license`)
- Persistence: Spring `JdbcClient` — no JPA/Hibernate
- No checkstyle or spotless configured
- Integration tests use Testcontainers with real PostgreSQL — Docker must be running

---

## How to run the project

```bash
# Build all modules
mvn install

# Run the example application
cd who-example && mvn spring-boot:run
```

---

## How to run tests

```bash
# Run all tests
mvn test

# Run tests for a single module
mvn test -pl who-core
```

Expected output when all tests pass:
```
[INFO] BUILD SUCCESS
```

---

## How to lint / type-check

```bash
mvn -P license verify
```

---

## Coding conventions

- All domain types are immutable Java records
- Service interfaces live in `who-core`; implementations are package-private in their respective module
- `@Transactional` belongs at the service layer — never on repositories
- JDBC repositories use `JdbcClient` exclusively — no JPA/Hibernate
- No Spring annotations (`@Service`, `@Component`, `@Autowired`, etc.) in `who-core` — core has zero Spring dependency
- Credential modules (`who-jwt`, `who-apikey`) are thin extraction adapters only — no identity resolution, no permission logic, no RBAC knowledge
- Constructor injection only — no `@Autowired`, no field injection, no setter injection
- `@Order` / `Ordered` for controlling `CredentialExtractor` precedence when multiple are registered
- All new Java files must have the Apache 2.0 license header

---

## Repository structure

The redesigned module layout (this is the target — the current codebase is being refactored toward this):

```
who-parent/                     Root POM, dependency management
│
├── who-core/                   Zero Spring dependency
│     domain/
│       Identity                Stable internal UUID record (id, status)
│       ExternalIdentity        (identityId, type, key) — a credential stored in DB
│       WhoPrincipal            (identityId, Set<String> permissions) — goes in SecurityContext
│     spi/
│       Credential              Marker interface for credential types
│       CredentialExtractor     HttpRequest → Optional<Credential>
│       PermissionsResolver     Identity → Set<String>
│     service/                  Service interfaces
│     repository/               Repository interfaces
│
├── who-jdbc/                   JDBC implementations of who-core repositories
│     Uses Spring JdbcClient
│     schema.sql — all who_* tables
│
├── who-rbac/                   PermissionsResolver implementation using roles/permissions
│     Role, Permission domain types
│     RbacPermissionsResolver implements PermissionsResolver
│     who_role, who_permission, who_user_role, who_role_permission tables
│
├── who-jwt/                    JWT CredentialExtractor
│     JwtCredential(issuer, subject) implements Credential
│     JwtCredentialExtractor    Reads Bearer token, validates JWT, extracts iss+sub
│     No knowledge of Identity or permissions
│
├── who-apikey/                 API key CredentialExtractor
│     ApiKeyCredential(keyHash) implements Credential
│     ApiKeyCredentialExtractor Reads X-API-Key header, hashes key
│     No knowledge of Identity or permissions
│
├── who-autoconfigure/          Spring Boot autoconfiguration for all modules
│
├── who-spring-boot-starter/    Convenience starter: core + jdbc + rbac + jwt
│
└── who-example/                Reference application demonstrating the library
```

---

## Definition of "done" for a spec

A spec is done when ALL of the following are true:

- [ ] The feature described in the spec is implemented
- [ ] All existing tests pass (`mvn test`)
- [ ] New tests cover the new behavior (unless the spec explicitly says otherwise)
- [ ] License headers present on all new Java files (`mvn -P license verify`)
- [ ] No `@Autowired` or field injection introduced anywhere
- [ ] No Spring dependencies leaked into `who-core`
- [ ] Constructor injection used throughout
- [ ] No debug code left in
- [ ] Public-facing interfaces and SPIs have JavaDoc
- [ ] README(s) and any other relevant documentation updated to reflect the change
- [ ] `progress.txt` updated with verification results

---

## Constraints and guardrails

- Never add Spring dependencies to `who-core` — it must remain a plain Java module
- Never put business logic in a credential module (`who-jwt`, `who-apikey`) — extraction only
- Never use JPA/Hibernate — `JdbcClient` only
- Never modify the public API of a module without a spec that explicitly calls for it
- Never leave a multi-step service operation without `@Transactional`
- Constructor injection only — never `@Autowired`, field injection, or setter injection

---

## Environment

This library relies entirely on Spring Boot configuration properties (`application.yml` /
`application-test.properties`). No external environment variables required by the library itself.
Consumer applications configure their datasource and JWT decoder via standard Spring Boot
properties.
