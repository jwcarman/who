Status: implemented
Original: specs/done/003-who-rbac.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

# who-rbac: RBAC PermissionsResolver

## What to build

Create the `who-rbac` Maven module providing a `PermissionsResolver` implementation
backed by a roles/permissions model. Includes the domain types, repository interfaces,
JDBC implementations, schema, and a management service for administering roles and
permissions.

### Module setup

New Maven module `who-rbac` with dependencies:
- `who-core`
- `spring-boot-starter-jdbc`
- `who-jdbc` (test scope — the schema references `who_identity`)
- `spring-boot-starter-test` (test scope)
- `org.testcontainers:testcontainers-postgresql`, `testcontainers-junit-jupiter` (test scope)
- `org.postgresql:postgresql` (test scope)

### Schema

Resource file at `src/main/resources/org/jwcarman/who/rbac/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS who_role (
    id   UUID        PRIMARY KEY,
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
```

Note: `who_identity_role.identity_id` references `who_identity` (owned by `who-jdbc`), so
the `who-jdbc` schema must run first. The original design had no FK here; it was added in
0.10.0 (see `2026-11-07-security-fixes-design.md`), so deleting an identity removes its
role assignments.

### Domain types

**`Role`** — immutable record: `id UUID`, `name String`. Static factory `create(String name)`
(id from `Identifiers.uuid()`).

Permissions are plain `String` values — no `Permission` entity needed.

**`RoleNotFoundException`** — extends `WhoException`; thrown by the by-name lookups.

### Repository interfaces

**`RoleRepository`**:
```java
Optional<Role> findById(UUID id);
Optional<Role> findByName(String name);
Role save(Role role);
boolean existsById(UUID id);
void deleteById(UUID id);
```

**`RolePermissionRepository`**:
```java
Set<String> findPermissionsByRoleId(UUID roleId);
Set<String> findPermissionsByRoleIds(Collection<UUID> roleIds);
void addPermission(UUID roleId, String permission);
void removePermission(UUID roleId, String permission);
void removeAllPermissionsForRole(UUID roleId);
```

**`IdentityRoleRepository`**:
```java
List<UUID> findRoleIdsByIdentityId(UUID identityId);
void assignRole(UUID identityId, UUID roleId);
void removeRole(UUID identityId, UUID roleId);
void removeAllRolesForIdentity(UUID identityId);
```

### JDBC implementations

`@Repository` classes using `JdbcClient`. `RoleRepository.save()` uses
`INSERT ... ON CONFLICT (id) DO UPDATE SET name = :name`. `addPermission` and `assignRole`
use `INSERT ... ON CONFLICT DO NOTHING` (idempotent).

### RbacPermissionsResolver

Implements `PermissionsResolver` (annotated `@Component`). Constructor-injected with
`IdentityRoleRepository` and `RolePermissionRepository`.

```java
Set<String> resolve(Identity identity) {
    List<UUID> roleIds = identityRoleRepository.findRoleIdsByIdentityId(identity.id());
    if (roleIds.isEmpty()) return Set.of();
    return rolePermissionRepository.findPermissionsByRoleIds(roleIds);
}
```

### RbacService

Annotated `@Service`; constructor-injected with `RoleRepository`, `RolePermissionRepository`
and `IdentityRoleRepository`. Management API:

```java
Role findRequiredRole(String name);                    // throws RoleNotFoundException
<R extends Enum<R>> Role findRequiredRole(R role);     // by role.name()
Role createRole(String name);                          // throws IllegalArgumentException if name exists
<R extends Enum<R>> Role createRole(R role);
void deleteRole(Role role);                            // throws if not found
void addPermissionToRole(Role role, String permission);      // throws if role not found
void removePermissionFromRole(Role role, String permission); // throws if not assigned
void assignRoleToIdentity(Identity identity, Role role);     // throws if role not found
void assignRoleByName(Identity identity, String roleName);   // throws RoleNotFoundException
<R extends Enum<R>> void assignRoleByName(Identity identity, R role);
void removeRoleFromIdentity(Identity identity, Role role);   // throws if not assigned
```

All mutating operations in `RbacService` are annotated `@Transactional`.

## Acceptance criteria

- [ ] `who-rbac` module exists and builds successfully
- [ ] Schema SQL exists at `src/main/resources/org/jwcarman/who/rbac/schema.sql`
- [ ] `Role` record exists with null-safe compact constructor
- [ ] `RoleRepository`, `RolePermissionRepository`, `IdentityRoleRepository` interfaces exist
- [ ] JDBC implementations exist for all three repositories using `ON CONFLICT` for upserts
- [ ] `RbacPermissionsResolver` implements `PermissionsResolver` and returns correct permission union
- [ ] `RbacPermissionsResolver.resolve()` returns empty set when identity has no roles
- [ ] `RbacService` throws `IllegalArgumentException` for invalid inputs (not found, duplicate), and `RoleNotFoundException` for unknown role names
- [ ] `RbacService` mutating operations are `@Transactional`
- [ ] Integration tests pass against a real PostgreSQL instance via Testcontainers covering the full role→permission→identity chain
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes
- [ ] Public classes and interfaces have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- `who_identity_role.identity_id` has a FK to `who_identity` with `ON DELETE CASCADE`; the `who-jdbc` schema must be created first
- Permissions are plain strings — no validation of format, no pre-registration required
- `deleteRole()` relies on `ON DELETE CASCADE` for `who_role_permission` and `who_identity_role` — no manual cleanup in service code
- Constructor injection only throughout
