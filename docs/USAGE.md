# Who Library - Usage Guide

## Configuration

### Database

Configure your database connection:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: dbuser
    password: dbpass
  jpa:
    hibernate:
      ddl-auto: update  # Use 'validate' in production
```

**Note:** The library uses Hibernate for schema generation. In production environments, consider using `ddl-auto: validate` and managing schema migrations with a dedicated tool.

### OAuth2 Resource Server

Configure trusted JWT issuers:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com
```

### Provisioning Policy

```yaml
who:
  provisioning:
    auto-provision: true  # Auto-create users for unknown identities
```

## Managing Roles and Permissions

### Creating Roles

```bash
POST /api/who/management/roles
{
  "roleName": "billing-admin"
}
```

### Adding Permissions to Roles

```bash
POST /api/who/management/roles/{roleId}/permissions
{
  "permission": "billing.invoice.read"
}
```

### Assigning Roles to Users

```bash
POST /api/who/management/users/{userId}/roles/{roleId}
```

## Using Preferences

### Get Preferences

```bash
GET /api/who/preferences/ui
```

Returns user's preferences for the "ui" namespace.

### Set Preferences

```bash
PUT /api/who/preferences/ui
{
  "theme": "dark",
  "locale": "en"
}
```

## Authorization

Use Spring Security's `@PreAuthorize` annotation:

```java
@PreAuthorize("hasAuthority('billing.invoice.write')")
public void createInvoice() { ... }
```

## Accessing the Principal

```java
@GetMapping("/profile")
public UserProfile getProfile(@AuthenticationPrincipal WhoPrincipal principal) {
    UUID userId = principal.userId();
    Set<String> permissions = principal.permissions();
    // ...
}
```
