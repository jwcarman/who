# Who - Spring Boot Identity & Entitlements Library

[![CI](https://github.com/jwcarman/who/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/who/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/who/main/pom.xml&query=//*[local-name()='java.version']/text()&label=Java&color=orange)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/org.jwcarman.who/who-spring-boot-starter)](https://central.sonatype.com/artifact/org.jwcarman.who/who-spring-boot-starter)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)

A reusable Spring Boot library that maps external JWT credentials to a stable internal identity UUID and enforces role-based access control.

**Requirements:** Java 25+, Spring Boot 4.1+ (built against 4.1.2), PostgreSQL 14+ required (the production database Who is tested against).

## Quick Start — Run the Example App

Clone the repo and be making authenticated API calls in under two minutes:

```bash
# Clone and build
git clone https://github.com/jwcarman/who
cd who
mvn install -DskipTests

# Run the example application
mvn spring-boot:run -pl who-example
```

Then:

1. Open `http://localhost:8080` in a browser
2. Click **Login** and log in as `alice` / `password`
3. Copy the access token shown on the page (the demo shows it for convenience; real apps never display tokens)
4. Use it:

```bash
curl http://localhost:8080/api/me \
  -H "Authorization: Bearer <paste-token-here>"

curl http://localhost:8080/api/tasks \
  -H "Authorization: Bearer <paste-token-here>"
```

**Pre-configured users:**

| Username | Password | Permissions                          |
|----------|----------|--------------------------------------|
| alice    | password | `task.read`                          |
| bob      | password | `task.read`, `task.write`            |
| admin    | password | `task.read`, `task.write`, `task.delete` |

---

## How it works

Every request passes through this pipeline:

```
HttpRequest → CredentialExtractor → Credential → Identity → Set<String> permissions → WhoPrincipal
```

`WhoJwtAuthenticationConverter` extracts the `iss` and `sub` claims from a validated JWT, looks up the matching `JwtCredential` in the database, and resolves it to an `Identity` UUID. The `PermissionsResolver` (backed by `who-rbac`) loads the effective permission strings for that identity. The resulting `WhoPrincipal` is placed in the Spring Security context, and controllers use `@PreAuthorize` with permission strings to authorize access — with no knowledge of whether the caller used a JWT or any other credential type.

---

## Add the dependency

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-spring-boot-starter</artifactId>
    <version>0.10.0</version>
</dependency>
```

---

## Upgrading to 0.10.0

0.10.0 is a security release. Some changes break existing code or data.

- **API key filter wiring.** `ApiKeyAuthenticationFilter` is no longer registered globally with the servlet container. Add it to each chain that should accept API keys: `http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults())`. The filter skips requests that are already authenticated. See [Wiring the filter](#wiring-the-filter).
- **Method security is on by default.** `@RequiresPermission` is now enforced without any setup. Set `who.method-security.enabled=false` to turn it off. An `@EnableMethodSecurity` in your own configuration is respected.
- **`ApiKeyCredentialRepository.deleteById` returns `boolean`.** It returns `true` if a key was deleted. Update custom implementations. To revoke a key, call `ApiKeyService.revoke(UUID)`.
- **New key and token format.** New API keys are `who_` plus 43 URL-safe characters (256 random bits). Enrollment tokens use the same random source. Existing keys and tokens keep working, because both are stored and looked up as hashes.
- **Enrollment exceptions.** `enroll` throws `EnrollmentIdentityInactiveException` for an identity that is not active, and `CredentialAlreadyLinkedException` for a credential that is already linked. A token is single use, even under concurrent requests. `EnrollmentTokenNotFoundException` no longer contains the token value, and `EnrollmentToken.toString()` masks the value.
- **JWT claims.** A token with no `iss` or no `sub` claim now gets 401 instead of 500. Validate the issuer too. See [Configure JWT](#configure-jwt).
- **Foreign key on `who_identity_role`.** `who_identity_role.identity_id` now references `who_identity(id)` with `ON DELETE CASCADE`. Deleting an identity removes its role assignments, and assigning a role to an identity that is not in `who_identity` now fails. The `CREATE TABLE IF NOT EXISTS` schema does not change existing tables, so on an existing database run this yourself. Delete orphan rows first, or the constraint fails:

```sql
DELETE FROM who_identity_role
WHERE identity_id NOT IN (SELECT id FROM who_identity);

ALTER TABLE who_identity_role
    ADD CONSTRAINT fk_identity_role_identity
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE;
```


---

## Database setup

Who ships its own schema files on the classpath, one per module. The autoconfiguration runs them automatically — only modules present on the classpath contribute scripts, and all scripts use `CREATE TABLE IF NOT EXISTS` so they are safe to run on every startup.

### Auto-initialization (default)

By default, schemas run automatically when the datasource is an embedded database (H2, HSQLDB, Derby). Control this with:

```yaml
who:
  initialize-schema: embedded  # always | embedded | never (default: embedded)
```

For production, set `who.initialize-schema: never` and manage the schema with Flyway or Liquibase instead.

The `who-rbac` schema requires the `who_identity` table from `who-jdbc` (`who_identity_role.identity_id` references it). Run the `who-jdbc` schema first. The Flyway and Liquibase copies below are already in that order.

### Option A: Flyway

Copy the following file to `src/main/resources/db/migration/V1__who.sql` in your application:

```sql
CREATE TABLE IF NOT EXISTS who_identity (
    id         UUID PRIMARY KEY,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP(9) NOT NULL,
    updated_at TIMESTAMP(9) NOT NULL
);

CREATE TABLE IF NOT EXISTS who_credential_identity (
    credential_id UUID PRIMARY KEY,
    identity_id   UUID NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_role (
    id   UUID         PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS who_role_permission (
    role_id    UUID         NOT NULL,
    permission VARCHAR(255) NOT NULL,
    PRIMARY KEY (role_id, permission),
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_identity_role (
    identity_id UUID NOT NULL,
    role_id     UUID NOT NULL,
    PRIMARY KEY (identity_id, role_id),
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_jwt_credential (
    id      UUID         PRIMARY KEY,
    issuer  VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject)
);

CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id       UUID         PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    key_hash VARCHAR(64)  NOT NULL UNIQUE
);

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

### Option B: Liquibase

Copy the following file to `src/main/resources/db/changelog/001-who.yaml` in your application:

```yaml
databaseChangeLog:
  - changeSet:
      id: who-identity
      author: who
      changes:
        - createTable:
            tableName: who_identity
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: status
                  type: varchar(20)
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamp
                  constraints:
                    nullable: false

  - changeSet:
      id: who-credential-identity
      author: who
      changes:
        - createTable:
            tableName: who_credential_identity
            columns:
              - column:
                  name: credential_id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: identity_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_credential_identity_identity
                    references: who_identity(id)
                    deleteCascade: true

  - changeSet:
      id: who-role
      author: who
      changes:
        - createTable:
            tableName: who_role
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: varchar(255)
                  constraints:
                    nullable: false
                    unique: true

  - changeSet:
      id: who-role-permission
      author: who
      changes:
        - createTable:
            tableName: who_role_permission
            columns:
              - column:
                  name: role_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_role_permission_role
                    references: who_role(id)
                    deleteCascade: true
              - column:
                  name: permission
                  type: varchar(255)
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: who_role_permission
            columnNames: role_id, permission

  - changeSet:
      id: who-identity-role
      author: who
      changes:
        - createTable:
            tableName: who_identity_role
            columns:
              - column:
                  name: identity_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_identity_role_identity
                    references: who_identity(id)
                    deleteCascade: true
              - column:
                  name: role_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_identity_role_role
                    references: who_role(id)
                    deleteCascade: true
        - addPrimaryKey:
            tableName: who_identity_role
            columnNames: identity_id, role_id

  - changeSet:
      id: who-jwt-credential
      author: who
      changes:
        - createTable:
            tableName: who_jwt_credential
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: issuer
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: subject
                  type: varchar(255)
                  constraints:
                    nullable: false
        - addUniqueConstraint:
            tableName: who_jwt_credential
            columnNames: issuer, subject

  - changeSet:
      id: who-api-key-credential
      author: who
      changes:
        - createTable:
            tableName: who_api_key_credential
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: key_hash
                  type: varchar(64)
                  constraints:
                    nullable: false
                    unique: true

  - changeSet:
      id: who-enrollment-token
      author: who
      changes:
        - createTable:
            tableName: who_enrollment_token
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: identity_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_enrollment_token_identity
                    references: who_identity(id)
                    deleteCascade: true
              - column:
                  name: token_value
                  type: varchar(255)
                  constraints:
                    nullable: false
                    unique: true
              - column:
                  name: status
                  type: varchar(20)
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: expires_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: redeemed_at
                  type: timestamp
```

---

## Configure JWT

Tell Who where your authorization server lives:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://your-auth-provider.com/.well-known/jwks.json
          # or: issuer-uri: https://your-auth-provider.com
```

Validate the issuer. With `issuer-uri`, Spring Security checks the `iss` claim for you. With `jwk-set-uri`, nothing checks it, so add a `JwtIssuerValidator` to your `JwtDecoder`. A `JwtDecoder` bean replaces the `jwk-set-uri` property, so keep the URL in one place:

```java
@Bean
JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder
        .withJwkSetUri("https://your-auth-provider.com/.well-known/jwks.json")
        .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://your-auth-provider.com"));
    return decoder;
}
```

A token with no `iss` or no `sub` claim gets 401.

Wire `WhoJwtAuthenticationConverter` into your resource server security filter chain:

```java
@Bean
@Order(2)
public SecurityFilterChain apiSecurityFilterChain(
        HttpSecurity http,
        WhoJwtAuthenticationConverter whoJwtAuthenticationConverter) throws Exception {
    http
        .securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .oauth2ResourceServer(rs -> rs
            .jwt(jwt -> jwt.jwtAuthenticationConverter(whoJwtAuthenticationConverter))
        );
    return http.build();
}
```

`WhoJwtAuthenticationConverter` is auto-configured by `who-autoconfigure` — inject it as a bean.

---

## API Key Authentication

Who supports API key authentication via `who-apikey`. API keys are hashed before storage — the raw key is shown once at creation and cannot be retrieved again.

### Wiring the filter

The filter is not registered globally. It only runs in chains that add it. Add it with `WhoApiKeySecurity`:

```java
http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
```

`WhoApiKeySecurity.apiKey()` takes the `ApiKeyAuthenticationFilter` bean that `who-autoconfigure` provides. To use your own instance, call `WhoApiKeySecurity.apiKey(filter)`. The filter goes before `AuthorizationFilter` and skips requests that are already authenticated.

### Configuring the header name

The default header is `X-API-Key`. Override it with:

```yaml
who:
  api-key:
    header-name: X-API-Key  # default
```

### Generating a key

```java
Identity identity = whoService.createIdentity();
String rawKey = apiKeyService.create(identity, "Production server");
// Show rawKey to the user once — it cannot be retrieved again
```

The returned key is `who_` followed by 43 URL-safe characters (256 random bits). Store it securely — the library stores only the hash.

### Using the key

```bash
curl https://api.example.com/endpoint \
  -H "X-API-Key: who_<your-key>"
```

### Revoking a key

Call `ApiKeyService.revoke` with the credential id:

```java
apiKeyService.revoke(credentialId);
```

This deletes the key and its identity link.

Revocation takes effect immediately — the next request using that key will be denied.

---

## Managing Identities

`WhoService` is the single entry point for creating identities. Do not write directly to `IdentityRepository` — go through the service.

```java
@Autowired WhoService whoService;

// Create a new ACTIVE identity with a generated UUID v7
Identity identity = whoService.createIdentity();
UUID identityId = identity.id();
```

All other operations — enrolling credentials, assigning roles, issuing API keys — take an `identityId` obtained this way.

---

## Enroll credentials

Before a JWT can authenticate, a `JwtCredential` row must exist for the `(issuer, subject)` pair and must be linked to an active `Identity`. Who does not auto-provision — access is denied for unknown credentials.

Take the `issuer` and `subject` from the authenticated redeemer's own validated JWT. Never take them from the request body.

**Using `WhoEnrollmentService` (recommended):**

```java
// 1. Create an identity via WhoService
Identity identity = whoService.createIdentity();

// 2. Issue an enrollment token and deliver token.value() to the user out of band
EnrollmentToken token = enrollmentService.createToken(identity);
notifyUser(token.value()); // email, admin console, etc.

// 3. The user logs in, then redeems the token. Persist the JwtCredential, then enroll.
//    issuer and subject come from the redeemer's validated JWT.
JwtCredential credential = jwtCredentialRepository.save(JwtCredential.create(issuer, subject));
Identity enrolled = enrollmentService.enroll(token.value(), credential);

// To cancel a token before it is redeemed:
enrollmentService.revokeToken(token);
```

A token can be redeemed once, even if two requests race. `enroll` throws:

- `EnrollmentTokenNotFoundException` if the token does not exist,
- `EnrollmentTokenExpiredException` if it has expired,
- `EnrollmentTokenNotPendingException` if it was already redeemed or revoked,
- `EnrollmentIdentityInactiveException` if the identity is not active,
- `CredentialAlreadyLinkedException` if the credential is already linked to an identity.

**Manual SQL insert (for bootstrapping / testing):**

```sql
-- Create identity
INSERT INTO who_identity (id, status, created_at, updated_at)
VALUES (gen_random_uuid(), 'ACTIVE', NOW(), NOW());

-- Create JWT credential
INSERT INTO who_jwt_credential (id, issuer, subject)
VALUES (gen_random_uuid(), 'https://your-issuer.com', 'alice');

-- Link credential to identity
INSERT INTO who_credential_identity (credential_id, identity_id)
VALUES (<credential_id>, <identity_id>);
```

---

## RBAC

Use `RbacService` to manage roles and assign them to identities:

```java
@Autowired WhoService whoService;
@Autowired RbacService rbacService;

// Create an identity
Identity identity = whoService.createIdentity();

// Create a role and grant permissions
Role editorRole = rbacService.createRole("editor");
rbacService.addPermissionToRole(editorRole, "task.read");
rbacService.addPermissionToRole(editorRole, "task.write");

// Assign the role to the identity
rbacService.assignRoleToIdentity(identity, editorRole);
```

To look up an existing role by name (throws `RoleNotFoundException` if absent):

```java
Role editorRole = rbacService.findRequiredRole("editor");
```

**Tip:** define your roles as an enum to avoid raw strings throughout your application:

```java
public enum AppRole { EDITOR, VIEWER, ADMIN }

// All three methods accept enum constants directly
Role editorRole = rbacService.createRole(AppRole.EDITOR);
rbacService.assignRoleByName(identity, AppRole.EDITOR);
Role found = rbacService.findRequiredRole(AppRole.EDITOR);
```

Roles can only be assigned to identities that exist in `who_identity`. Deleting an identity removes its role assignments.

Permissions resolve transitively through all roles assigned to an identity. Method security is on by default, so `@RequiresPermission` is enforced with no extra setup. Set `who.method-security.enabled=false` to turn it off. If your application has its own `@EnableMethodSecurity`, Who respects it. Use `@RequiresPermission` from `who-spring-security` to authorize methods:

```java
@GetMapping("/tasks")
@RequiresPermission("task.read")
public List<Task> getTasks(@AuthenticationPrincipal WhoPrincipal principal) {
    return taskService.findAll(principal.identity().id());
}
```

For larger applications, use `@RequiresPermission` as a meta-annotation to build a typed permission vocabulary with no raw strings at the call site:

```java
public interface TaskPermissions {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @RequiresPermission("task.read")
    @interface Read {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @RequiresPermission("task.write")
    @interface Write {}
}

// Usage — no strings at the call site:
@TaskPermissions.Read
public List<Task> getTasks(...) { ... }
```

---

## Configuration reference

| Property | Default | Description |
|----------|---------|-------------|
| `who.initialize-schema` | `embedded` | When to run Who's bundled DDL scripts: `always`, `embedded` (H2/HSQLDB/Derby only), or `never` |
| `who.enrollment.token-expiration` | `24h` | How long a newly issued enrollment token is valid (ISO-8601 duration, e.g. `PT12H`) |
| `who.method-security.enabled` | `true` | Enables method security so `@RequiresPermission` is enforced. Set to `false` to turn it off |
| `who.api-key.header-name` | `X-API-Key` | HTTP header used to pass API keys |

---

## Modules

| Module | Description |
|--------|-------------|
| `who-core` | Domain types (`Identity`, `WhoPrincipal`), service and SPI interfaces — no Spring dependency |
| `who-jdbc` | JDBC implementations of core repositories using Spring `JdbcClient` |
| `who-rbac` | `RbacService` and `PermissionsResolver` backed by roles and permissions |
| `who-jwt` | `WhoJwtAuthenticationConverter` and `JwtCredential` extraction |
| `who-apikey` | `ApiKeyAuthenticationFilter` and `ApiKeyService` for API key issuance and authentication |
| `who-enrollment` | `WhoEnrollmentService` for issuing and redeeming credential enrollment tokens |
| `who-autoconfigure` | Spring Boot autoconfiguration for all modules |
| `who-spring-boot-starter` | Convenience starter: pulls in all of the above |

---

## Build

```bash
mvn clean install
```

Run with tests and code coverage:

```bash
mvn clean verify -Pci
```
