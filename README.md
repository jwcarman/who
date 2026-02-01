# Who - Spring Boot Identity & Entitlements Framework

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

A reusable Spring Boot library for OAuth2/JWT authentication, internal identity mapping, RBAC authorization, user invitations, and personalization.

## Features

- **JWT Authentication**: OAuth2 resource server with multi-issuer support and JWT validation
- **Internal Identity Mapping**: Map external OAuth2 identities `(issuer, subject)` to stable internal UUIDs
- **RBAC Authorization**: Role-Based Access Control with atomic permission strings as Spring Security authorities
- **User Invitations**: Email-based user invitation system with JWT-authenticated token acceptance
- **Contact Methods**: Email and phone contact management with verification workflows
- **User Preferences**: Namespaced JSON preferences with defaults and deep merge
- **Identity Linking**: Link multiple external identities to one internal user
- **Auto-Provisioning**: Configurable policy for handling unknown identities (auto-create or deny)

## Modules

- **who-core** - Core domain types, service interfaces, and business logic
- **who-jdbc** - JDBC repository implementations using Spring JdbcClient
- **who-security** - Spring Security OAuth2 integration and authentication converters
- **who-web** - REST controllers for management, invitations, and preferences (optional)
- **who-autoconfigure** - Spring Boot autoconfiguration
- **who-spring-boot-starter** - Convenience starter aggregating all modules

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Application

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-auth-provider.com

who:
  provisioning:
    auto-provision: false  # Set to true to auto-create users on first login
  web:
    mount-point: /api/who  # Base path for Who web controllers (default)
```

### 3. Implement Required SPI

Provide an implementation of `InvitationNotifier` to send invitation emails:

```java
@Component
public class EmailInvitationNotifier implements InvitationNotifier {

    @Override
    public void sendInvitation(Invitation invitation) {
        // Send email to invitation.email() with invitation.token()
        emailService.send(invitation.email(),
            "You're invited!",
            "Accept your invitation: " + buildAcceptUrl(invitation.token()));
    }
}
```

### 4. Use in Controllers

```java
@RestController
public class BillingController {

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing.invoice.read')")
    public List<Invoice> getInvoices(@AuthenticationPrincipal WhoPrincipal principal) {
        UUID userId = principal.userId();
        return invoiceService.findByUserId(userId);
    }
}
```

## Core Domain Models

| Model | Purpose |
|-------|---------|
| **User** | Immutable user entity with UUID, status (ACTIVE/SUSPENDED/DISABLED), timestamps |
| **ExternalIdentity** | Maps (issuer, subject) JWT claims to internal userId |
| **Role** | Named role grouping permissions (e.g., "admin", "billing-user") |
| **Permission** | Atomic permission string (e.g., "billing.invoice.read") |
| **Invitation** | Email-based user invitation with token, expiration, role assignment |
| **ContactMethod** | Email/phone contact with verification status |
| **UserPreferences** | Namespaced JSON preferences storage |
| **WhoPrincipal** | Authentication principal with userId and resolved permissions |

## Services

### UserService

Manages user lifecycle and role assignments:

```java
UUID createUser(UserStatus status)
void activateUser(UUID userId)
void deactivateUser(UUID userId)
void deleteUser(UUID userId)
void assignRoleToUser(UUID userId, UUID roleId)
void removeRoleFromUser(UUID userId, UUID roleId)
Set<String> resolvePermissions(UUID userId)  // Returns effective permissions
```

### RbacService

Manages roles and permissions:

```java
UUID createRole(String roleName)
void deleteRole(UUID roleId)
void addPermissionToRole(UUID roleId, String permission)
void removePermissionFromRole(UUID roleId, String permission)
```

### InvitationService

Manages user invitations:

```java
Invitation create(String email, UUID roleId)  // Auto-revokes existing pending invitations
Invitation accept(String token)  // Creates user, links identity, assigns role
void revoke(UUID invitationId)
List<Invitation> list(InvitationStatus status, Instant since)
Optional<Invitation> findByToken(String token)
```

**Invitation Flow:**
1. Admin creates invitation with email and role → invitation token generated
2. `InvitationNotifier` SPI sends email with token
3. User clicks link → frontend calls `/api/who/invitations/accept` with JWT + token
4. Service creates user, links JWT identity, assigns role, creates verified contact method

### ContactMethodService

Manages email/phone contacts:

```java
ContactMethod createUnverified(UUID userId, ContactType type, String value)
ContactMethod createVerified(UUID userId, ContactType type, String value)
ContactMethod markVerified(UUID contactMethodId)
List<ContactMethod> findByUserId(UUID userId)
Optional<ContactMethod> findByUserIdAndType(UUID userId, ContactType type)
void delete(UUID contactMethodId)
```

### PreferencesService

Manages namespaced JSON preferences:

```java
<T> T getPreferences(UUID userId, String namespace, Class<T> type)
<T> void setPreferences(UUID userId, String namespace, T preferences)
<T> T mergePreferences(Class<T> type, T... layers)  // Deep merge with later overriding
```

**Example:**
```java
// Get user's dashboard preferences
DashboardPrefs prefs = preferencesService.getPreferences(
    userId, "dashboard", DashboardPrefs.class);

// Merge defaults with user overrides
DashboardPrefs effective = preferencesService.mergePreferences(
    DashboardPrefs.class, systemDefaults, userPrefs);
```

### IdentityService

Manages external identity linkage:

```java
void linkExternalIdentity(UUID userId, String issuer, String subject)
void unlinkExternalIdentity(UUID userId, UUID externalIdentityId)
```

## REST API

### Role Management (requires `who.role.*` permissions)

```
POST   /api/who/management/roles                              (who.role.create)
DELETE /api/who/management/roles/{roleId}                     (who.role.delete)
POST   /api/who/management/roles/{roleId}/permissions         (who.role.permission.add)
DELETE /api/who/management/roles/{roleId}/permissions/{perm}  (who.role.permission.remove)
```

**Example:**
```bash
# Create role
curl -X POST /api/who/management/roles \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"billing-admin"}'

# Add permission to role
curl -X POST /api/who/management/roles/$ROLE_ID/permissions \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"permission":"billing.invoice.read"}'
```

### User-Role Management (requires `who.user.role.*` permissions)

```
POST   /api/who/management/users/{userId}/roles/{roleId}      (who.user.role.assign)
DELETE /api/who/management/users/{userId}/roles/{roleId}      (who.user.role.remove)
```

### Invitations

```
POST   /api/who/invitations                      (who.invitation.create)
POST   /api/who/invitations/accept?token=...     (JWT authenticated - no permission required)
DELETE /api/who/invitations/{invitationId}       (who.invitation.revoke)
GET    /api/who/invitations                      (who.invitation.list)
GET    /api/who/invitations/{token}              (public validation endpoint)
```

**Example Invitation Flow:**
```bash
# Admin creates invitation
curl -X POST /api/who/invitations \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","roleId":"$ROLE_ID"}'

# User accepts invitation (must use JWT with matching email claim)
curl -X POST /api/who/invitations/accept?token=$TOKEN \
  -H "Authorization: Bearer $USER_JWT"
```

### Preferences (authenticated users)

```
GET    /api/who/preferences/{namespace}          (returns user's preferences)
PUT    /api/who/preferences/{namespace}          (updates user's preferences)
```

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `who.web.mount-point` | `/api/who` | Base path for Who web controllers |
| `who.provisioning.auto-provision` | `false` | Auto-create users for unknown JWT identities |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | (required) | OAuth2 JWT issuer URL |

## Extension Points (SPIs)

### InvitationNotifier (Required)

Send invitation emails to users:

```java
public interface InvitationNotifier {
    void sendInvitation(Invitation invitation);
}
```

### UserProvisioningPolicy (Optional)

Control how unknown external identities are handled:

```java
public interface UserProvisioningPolicy {
    UUID handleUnknownIdentity(ExternalIdentityKey identityKey);
}
```

**Built-in implementations:**
- `DenyUnknownIdentityPolicy` (default) - Denies access
- `AutoProvisionIdentityPolicy` - Creates user with ACTIVE status

### ContactVerificationNotifier (Optional)

Send verification codes to contact methods:

```java
public interface ContactVerificationNotifier {
    void sendVerificationCode(ContactMethod contact, String code);
}
```

### ContactConfirmationNotifier (Optional)

Notify users when contact methods are added:

```java
public interface ContactConfirmationNotifier {
    void notifyContactAdded(ContactMethod contact, User user);
}
```

## Authentication Flow

1. Client sends JWT in `Authorization: Bearer <token>` header
2. Spring Security validates JWT signature and claims (iss, sub, exp, aud)
3. `WhoAuthenticationConverter` extracts issuer and subject from JWT
4. `IdentityResolver` looks up `(issuer, subject)` in database:
   - If found → returns userId
   - If not found → applies `UserProvisioningPolicy` (deny or auto-create)
5. `UserService.resolvePermissions()` loads user's effective permissions:
   - Query user's assigned roles
   - Load all permissions for those roles
   - Deduplicate and return as Set
6. Build `WhoPrincipal` with userId and permissions
7. Map permissions to Spring Security `SimpleGrantedAuthority`
8. Controllers use `@PreAuthorize("hasAuthority('permission')")` to check access

## Database Schema

The library requires the following tables (created automatically via `schema.sql`):

- `who_user` - User entities
- `who_role` - Role definitions
- `who_permission` - Permission definitions
- `who_user_role` - User-role assignments (many-to-many)
- `who_role_permission` - Role-permission assignments (many-to-many)
- `who_external_identity` - External identity mappings
- `who_user_preference` - User preferences (namespaced JSON)
- `who_invitation` - User invitations
- `who_contact_method` - Contact methods (email/phone)

## Example Application

See `who-example` module for a complete working example with:
- OAuth2 authorization server configuration
- Sample `InvitationNotifier` implementation
- REST controller examples
- H2 in-memory database setup

## Build

```bash
mvn clean install
```

Run with tests and code coverage:
```bash
mvn clean verify -Pci
```

## Requirements

- Java 25+
- Spring Boot 4.0+
- Spring Security 7.0+
- JDBC DataSource

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
