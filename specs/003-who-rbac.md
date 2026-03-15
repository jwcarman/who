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
- `spring-boot-starter-test` + H2 (test scope)

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
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE
);
```

Note: `who_identity_role` has no FK on `identity_id` — the RBAC module does not own
the identity table. The application must ensure referential integrity at the service level.

### Domain types

**`Role`** — immutable record: `id UUID`, `name String`. Static factory `create(UUID id, String name)`.

Permissions are plain `String` values — no `Permission` entity needed.

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
`INSERT ... ON CONFLICT (id) DO UPDATE SET name = :name`.

### RbacPermissionsResolver

Implements `PermissionsResolver`. Constructor-injected with `IdentityRoleRepository`
and `RolePermissionRepository`.

```java
Set<String> resolve(Identity identity) {
    List<UUID> roleIds = identityRoleRepository.findRoleIdsByIdentityId(identity.id());
    if (roleIds.isEmpty()) return Set.of();
    return rolePermissionRepository.findPermissionsByRoleIds(roleIds);
}
```

### RbacService

Plain Java class (no Spring annotations). Constructor-injected. Management API:

```java
UUID createRole(String name);               // throws if name already exists
void deleteRole(UUID roleId);               // throws if not found
void addPermissionToRole(UUID roleId, String permission);    // throws if role not found
void removePermissionFromRole(UUID roleId, String permission); // throws if not found/assigned
void assignRoleToIdentity(UUID identityId, UUID roleId);    // throws if role not found
void removeRoleFromIdentity(UUID identityId, UUID roleId);  // throws if not found/assigned
```

All multi-step operations in `RbacService` must be annotated `@Transactional`.

## Acceptance criteria

- [ ] `who-rbac` module exists and builds successfully
- [ ] Schema SQL exists at `src/main/resources/org/jwcarman/who/rbac/schema.sql`
- [ ] `Role` record exists with null-safe compact constructor
- [ ] `RoleRepository`, `RolePermissionRepository`, `IdentityRoleRepository` interfaces exist
- [ ] JDBC implementations exist for all three repositories using `ON CONFLICT DO UPDATE` for upserts
- [ ] `RbacPermissionsResolver` implements `PermissionsResolver` and returns correct permission union
- [ ] `RbacPermissionsResolver.resolve()` returns empty set when identity has no roles
- [ ] `RbacService` throws `IllegalArgumentException` for invalid inputs (not found, duplicate)
- [ ] `RbacService` multi-step operations are `@Transactional`
- [ ] Integration tests pass against H2 in PostgreSQL mode covering the full role→permission→identity chain
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes
- [ ] Public classes and interfaces have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- `who-rbac` has no FK from `who_identity_role.identity_id` to `who_identity` — RBAC does not own identity storage; the application manages this relationship
- Permissions are plain strings — no validation of format, no pre-registration required
- `deleteRole()` should rely on `ON DELETE CASCADE` for `who_role_permission` and `who_identity_role` — no manual cleanup needed in service code
- Constructor injection only throughout
