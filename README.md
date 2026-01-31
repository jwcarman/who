# Who - Spring Boot Identity & Entitlements Framework

A reusable Spring Boot library for OAuth2/JWT authentication, internal identity mapping, RBAC authorization, and user preferences.

## Features

- **JWT Authentication**: OAuth2 resource server with multi-issuer support
- **Internal Identity Mapping**: Map external `(issuer, subject)` to stable internal user IDs
- **RBAC Authorization**: Role-based permissions as Spring Security authorities
- **User Preferences**: Namespaced JSON preferences with defaults and deep merge
- **Identity Linking**: Link multiple external identities to one internal user
- **Auto-Provisioning**: Configurable policy for handling unknown identities

## Modules

- `who-core` - Core domain types and interfaces
- `who-jpa` - JPA entities and repositories (Hibernate auto-generation)
- `who-security` - Spring Security integration
- `who-web` - REST controllers (optional)
- `who-starter` - Spring Boot auto-configuration

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-starter</artifactId>
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
    auto-provision: false  # Set to true to auto-create users
```

### 3. Use in Controllers

```java
@RestController
public class BillingController {

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing.invoice.read')")
    public List<Invoice> getInvoices(@AuthenticationPrincipal WhoPrincipal principal) {
        UUID userId = principal.userId();
        // ... use userId to fetch user-specific data
    }
}
```

## Build

```bash
mvn clean install
```

## Documentation

See [docs/USAGE.md](docs/USAGE.md) for detailed usage instructions.

## License

TBD
