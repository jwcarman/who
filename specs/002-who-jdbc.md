# who-jdbc: JDBC implementations of core repositories

## What to build

Create the `who-jdbc` Maven module providing JDBC implementations of the two core
repository interfaces from `who-core`. This module also owns the database schema for
the tables it manages.

### Module setup

New Maven module `who-jdbc` with dependencies:
- `who-core` (the project module)
- `spring-boot-starter-jdbc` (for `JdbcClient`)
- `spring-boot-starter-test` + H2 (test scope)

### Schema

Resource file at `src/main/resources/org/jwcarman/who/jdbc/schema.sql`:

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
```

### JdbcIdentityRepository

Implements `IdentityRepository` using `JdbcClient`. Use `@Repository`.

`save(Identity identity)` must use a single atomic upsert statement:
```sql
INSERT INTO who_identity (id, status, created_at, updated_at)
VALUES (:id, :status, :createdAt, :updatedAt)
ON CONFLICT (id) DO UPDATE SET status = :status, updated_at = :updatedAt
```

### JdbcCredentialIdentityRepository

Implements `CredentialIdentityRepository` using `JdbcClient`. Use `@Repository`.

- `findIdentityIdByCredentialId(UUID credentialId)` — SELECT by credential_id
- `link(UUID credentialId, UUID identityId)` — INSERT, conflict on credential_id
  should be an error (a credential can only be linked to one identity)
- `unlink(UUID credentialId)` — DELETE by credential_id

### Tests

Integration tests using H2 in PostgreSQL compatibility mode. Test configuration should
initialize the schema from `schema.sql`. Cover:
- Save new identity, retrieve it
- Update identity status via save (upsert)
- `existsById` returns true/false correctly
- `deleteById` removes the record
- `link` maps a credential UUID to an identity
- `findIdentityIdByCredentialId` returns the correct identity UUID
- `unlink` removes the mapping
- Deleting an identity cascades to remove its credential mappings

## Acceptance criteria

- [ ] `who-jdbc` module exists and builds successfully
- [ ] `who-jdbc/pom.xml` declares `who-core`, `spring-boot-starter-jdbc` as dependencies
- [ ] `schema.sql` exists at `src/main/resources/org/jwcarman/who/jdbc/schema.sql`
- [ ] `JdbcIdentityRepository` implements `IdentityRepository` using `INSERT ... ON CONFLICT DO UPDATE` — no UPDATE-then-INSERT pattern
- [ ] `JdbcCredentialIdentityRepository` implements `CredentialIdentityRepository`
- [ ] Deleting an identity cascades to remove its entries from `who_credential_identity`
- [ ] Integration tests pass against H2 in PostgreSQL compatibility mode
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes
- [ ] Public classes have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- Constructor injection only — `JdbcClient` injected via constructor, no `@Autowired`
- No UPDATE-then-INSERT upsert — use `ON CONFLICT DO UPDATE` exclusively
- H2 test datasource should use `MODE=PostgreSQL` in the JDBC URL
- `link()` throwing on duplicate credential_id is intentional — a credential maps to exactly one identity; if re-linking is needed it must be explicitly unlinked first
- Column names use snake_case; parameter names use camelCase (JdbcClient named params)
