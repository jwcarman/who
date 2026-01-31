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
- `who-autoconfigure` - Spring Boot autoconfiguration
- `who-spring-boot-starter` - Spring Boot starter (aggregates all modules)

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

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
