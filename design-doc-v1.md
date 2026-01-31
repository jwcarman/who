# Project: `who` — Spring Boot Identity, Entitlements (RBAC), and Personalization Framework

## 1) Purpose
Build a reusable Spring Boot library called **`who`** (published under the `org.jwcarman` namespace) that provides a consistent foundation for:

1) **Authentication integration** for APIs receiving **OAuth2/JWTs**
2) **Internal identity mapping** from external identities `(issuer, subject)` to a stable internal user ID
3) **Authorization (Entitlements)** using application-defined atomic permission strings and optional RBAC roles
4) **Personalization** via namespaced JSON preferences with defaults + merge behavior
5) **Guarded identity management services/APIs** for safe operations (linking identities, managing roles, managing preferences)

The goal is to avoid rebuilding these concepts in every Spring Boot application.

This design is **single-tenant / tenant-agnostic**: no multi-tenant concepts are required.

---

## 2) Publishing / Naming / Packaging

### 2.1 Maven coordinates
- **Group ID:** `org.jwcarman.who`

Recommended artifacts:
- `org.jwcarman.who:who-core`
- `org.jwcarman.who:who-jpa`
- `org.jwcarman.who:who-security`
- `org.jwcarman.who:who-web` (optional)
- `org.jwcarman.who:who-starter`

### 2.2 Base Java package
All code lives under:
- **`org.jwcarman.who`**

Suggested package layout:
- `org.jwcarman.who.core`
- `org.jwcarman.who.jpa`
- `org.jwcarman.who.security`
- `org.jwcarman.who.web`
- `org.jwcarman.who.autoconfig`

---

## 3) Design Principles

### 3.1 Identity vs Authorization
- JWTs are used for **authentication / identification**.
- The library will not rely on JWT scopes/claims for fine-grained authorization.
- Authorization is derived from internal roles/permissions stored in the application database.

### 3.2 Stable Internal User Identity
- Every user has a stable internal `UUID userId`.
- External login identities are mapped to internal users by `(issuer, subject)`:
  - `issuer` = JWT `iss`
  - `subject` = JWT `sub`

### 3.3 Permissions are Strings (Simple + Compatible with Spring Security)
- Atomic permissions are strings (e.g., `billing.invoice.write`).
- Application authors define the permission strings (constants recommended).
- Spring Security enforcement uses `hasAuthority("permission.string")`.

### 3.4 RBAC is Optional and Simple
- Users can be assigned to roles.
- Roles contain permissions.
- Effective permissions = union of permissions from all assigned roles (deduped).

### 3.5 Personalization is Separate from Security
- Preferences are not used for authorization decisions.
- Preferences are stored as JSON per namespace.

---

## 4) Deliverables / Modules

### 4.1 Recommended multi-module layout
- `who-core`
  - Core domain types, interfaces, and shared logic.
- `who-jpa`
  - JPA entities, repositories, and default persistence implementation.
- `who-security`
  - Spring Security integration: JWT validation, principal building, authorities mapping.
- `who-web` (optional but recommended)
  - REST controllers for common identity + role + preference operations.

### 4.2 Spring Boot Starter
Provide a starter dependency:
- `org.jwcarman.who:who-starter`

The starter should auto-register:
- Spring Security integration (resource server)
- JPA repositories/entities (if JPA is on the classpath)
- Default service implementations (if app doesn’t override)

Applications should only need to configure trusted JWT issuers/audiences and database connectivity.

---

## 5) Runtime Authentication + Authorization Flow

### 5.1 Incoming request handling
For each request:
1) Extract JWT from `Authorization: Bearer <jwt>`
2) Validate JWT:
   - signature validation (JWKS)
   - `iss` allowlist / registry
   - `aud` validation
   - `exp`, `nbf`
   - algorithm allowlist
   - JWKS caching + rotation support
3) Extract external identity key:
   - `issuer = iss`
   - `subject = sub`
4) Resolve internal user:
   - lookup `(issuer, subject)` in `who_external_identity`
   - returns `userId`
   - if identity is not found, behavior is configurable:
     - deny by default, OR
     - auto-provision a new internal user
5) Resolve entitlements:
   - load roles assigned to `userId`
   - load permissions for each role
   - compute union/dedupe `Set<String> permissions`
6) Build `WhoPrincipal`
7) Map permissions to Spring Security authorities:
   - each permission string becomes `SimpleGrantedAuthority(permission)`

### 5.2 Spring Security enforcement
Applications can enforce authorization using standard mechanisms:
- `@PreAuthorize("hasAuthority('billing.invoice.write')")`
- `@PreAuthorize("hasAnyAuthority('x.y.a','x.y.b')")`
- `SecurityContextHolder` / `Authentication` inspection

---

## 6) Core Domain Types

### 6.1 WhoPrincipal
A principal object used by Spring Security authentication:
- `UUID userId`
- `Set<String> permissions`

External identity information (issuer/subject) is available in the JWT but not carried in the principal. The principal focuses on authorization (internal user identity and permissions) rather than authentication details.

### 6.2 ExternalIdentityKey
- `String issuer`
- `String subject`

---

## 7) Persistence Model (JPA)

### 7.1 Users
`who_user`
- `id UUID PK`
- `status VARCHAR` (ACTIVE / SUSPENDED / DISABLED)
- `created_at TIMESTAMP`
- `updated_at TIMESTAMP`

### 7.2 External Identities
`who_external_identity`
- `id UUID PK`
- `user_id UUID FK -> who_user(id)`
- `issuer VARCHAR NOT NULL`
- `subject VARCHAR NOT NULL`
- `provider_hint VARCHAR NULL`
- `first_seen_at TIMESTAMP NULL`
- `last_seen_at TIMESTAMP NULL`
- Unique constraint on `(issuer, subject)`

This allows one internal user to have multiple external login identities over time.

### 7.3 Roles
`who_role`
- `id UUID PK`
- `name VARCHAR NOT NULL`
- Unique constraint on `(name)`

### 7.4 Role Permissions
`who_role_permission`
- `role_id UUID FK -> who_role(id)`
- `permission VARCHAR NOT NULL`
- Unique constraint on `(role_id, permission)`

Permissions are stored as strings and treated as stable identifiers.

### 7.5 User Roles
`who_user_role`
- `user_id UUID FK -> who_user(id)`
- `role_id UUID FK -> who_role(id)`
- Unique constraint on `(user_id, role_id)`

### 7.6 Preferences (Personalization)
`who_user_preferences`
- `user_id UUID FK -> who_user(id)`
- `namespace VARCHAR NOT NULL`
- `prefs_json TEXT/JSONB NOT NULL`
- `version BIGINT` (optional optimistic locking)
- `updated_at TIMESTAMP`
- Unique constraint on `(user_id, namespace)`

---

## 8) Entitlements Resolution

### 8.1 Effective permissions algorithm
Given `userId`:
1) Load roles for the user.
2) Load permissions for those roles.
3) Deduplicate with `Set<String>`.
4) Return effective permission set.

### 8.2 Permission naming conventions (recommended)
- Use a consistent convention like:
  - `service.resource.action`
  - examples: `billing.invoice.read`, `billing.invoice.write`
- Permission identifiers should be treated as stable (do not rename identifiers).
  - If a rename is desired, create a new permission identifier and migrate.

---

## 9) Personalization (Preferences)

### 9.1 Namespaces
Preferences are stored per namespace to avoid a single giant preferences object.

Examples:
- `global` (locale, timezone, theme)
- `ui`
- `billing`
- `reports`

Namespaces are owned by the application/modules.

### 9.2 Storage format
- Preferences are stored as JSON objects (nested objects allowed).
- The stored JSON typically represents user overrides.

### 9.3 Defaults and deserialization
Applications can define preference classes with default values:
- missing JSON fields should use defaults from the class upon deserialization

Example approach:
- POJO fields initialized with defaults
- avoid nulls overwriting defaults (prefer primitives or null-skipping config)

### 9.4 Generic merge utility
Provide a generic merge function:

- `mergePreferences(Class<P> type, P... layers) -> P`

Rules:
- later layers override earlier layers
- deep merge JSON objects
- arrays are replaced (not merged)
- null values are skipped for layering merges (do not wipe defaults)

Implementation strategy:
1) Convert each `P` to `JsonNode` using Jackson
2) Deep merge `ObjectNode` recursively
3) Convert final merged node back to `P`

### 9.5 Effective preferences retrieval
For `getEffectivePrefs(userId, namespace, Class<P>)`:
- combine:
  - application defaults (via POJO defaults or defaults provider)
  - user overrides stored in DB
- return final typed preference object

---

## 10) Guarded Identity and Authorization Management Services

### 10.1 Identity linking requirements
The library must support linking multiple external identities to one internal user.

Rules:
- External identity uniqueness is enforced by `(issuer, subject)` unique constraint.
- Linking must fail if `(issuer, subject)` is already linked to another user.
- Do not auto-link identities based on email matching.

### 10.2 Core management operations (service layer)
Provide a service API such as `WhoManagementService`:

#### External identity management
- `linkExternalIdentity(userId, issuer, subject)`
- `unlinkExternalIdentity(userId, externalIdentityId)`
  - must not allow unlinking the last remaining external identity for a user (unless admin override)

#### Role management
- `createRole(roleName)`
- `deleteRole(roleId)` (optional)
- `assignRoleToUser(userId, roleId)`
- `removeRoleFromUser(userId, roleId)`

#### Permission management for roles
- `addPermissionToRole(roleId, permission)`
- `removePermissionFromRole(roleId, permission)`

#### Preferences management
- `getUserPreferences(userId, namespace)`
- `setUserPreferences(userId, namespace, prefsJson)` (replace)
- `patchUserPreferences(userId, namespace, patchJson)` (optional)

### 10.3 Guarding rules (recommended)
- Only allow users to manage their own preferences and identities unless explicitly configured for admin capabilities.
- Prefer command-style operations over “generic update” endpoints.
- Emit audit events for sensitive mutations.

---

## 11) Spring Security Integration Details

### 11.1 OAuth2 Resource Server integration
Use Spring Security OAuth2 Resource Server:
- configure one or more trusted issuers
- validate JWTs using JWKS

### 11.2 Authentication conversion
Provide a custom `JwtAuthenticationConverter` that:
- reads `iss` and `sub`
- resolves `userId`
- resolves effective permissions
- returns `Authentication` containing:
  - `WhoPrincipal` as principal
  - authorities = permissions

---

## 12) Extensibility Hooks

Applications should be able to override key behaviors by defining beans:

- `UserProvisioningPolicy`
  - deny vs auto-provision unknown external identities
- `EntitlementsService`
  - custom permission resolution logic
- `PreferencesDefaultsProvider`
  - optional provider for default preferences per namespace/type
- `PreferencesStore`
  - alternate persistence if not using JPA
- `AuditSink`
  - where audit events go (DB, log, Kafka, etc.)

---

## 13) Non-goals (v1)
- No multi-tenant enforcement or tenant-aware authorization
- No complex policy language (OPA/Rego)
- No JWT scopes for fine-grained permissions
- No requirement for typed permissions/enums
- No provider-specific identity linking features (works with any JWT issuer)

---

## 14) Implementation Notes
- Java 17+ (prefer Java 21 if possible)
- Spring Boot 3.x
- Spring Security 6.x
- JPA/Hibernate for persistence
- Jackson for JSON preferences and merge
- Flyway migrations recommended for schema management

---

## 15) Acceptance Criteria
A Spring Boot application that adds `who-starter` can:
1) Validate JWTs from configured issuers
2) Resolve `(issuer, subject)` to internal `userId`
3) Load roles → permissions and expose them as Spring Security authorities
4) Enforce authorization using `@PreAuthorize(hasAuthority(...))`
5) Store and retrieve namespaced preferences as JSON with defaults and merge behavior
6) Link multiple external identities to one user and manage roles/permissions through library services (and optional web controllers)

