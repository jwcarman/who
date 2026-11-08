# Who

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

Identity and permissions for Spring Boot applications. Who maps the credentials a
caller presents (a JWT, an API key) to one stable internal identity, resolves that
identity's permissions, and lets your controllers check permission strings without
knowing which credential the caller used.

**📖 Documentation: [jwcarman.github.io/who](https://jwcarman.github.io/who/) · API reference: [jwcarman.github.io/who/api](https://jwcarman.github.io/who/api/)**

## Requirements

- Java 25+
- Spring Boot 4.1+ (built against 4.1.2)
- PostgreSQL 14+ required. It is the database Who is tested against.

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-spring-boot-starter</artifactId>
    <version>0.12.0</version>
</dependency>
```

### 2. Create the tables

Who ships one schema file per module. Set `who.initialize-schema: always` for local
development, or copy the schema into Flyway or Liquibase. See
[Database Setup](https://jwcarman.github.io/who/guides/database/).

### 3. Check permissions

```java
@GetMapping("/tasks")
@RequiresPermission("task.read")
public List<Task> getTasks(@AuthenticationPrincipal WhoPrincipal principal) {
    return taskService.findAll(principal.identity().id());
}
```

Method security is on by default. Wire the credentials you accept with the
[JWT](https://jwcarman.github.io/who/guides/jwt/) and
[API Keys](https://jwcarman.github.io/who/guides/api-keys/) guides, and link a
user's credential to an identity with
[Enrollment](https://jwcarman.github.io/who/guides/enrollment/).

### Try the example app

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-example
```

Open `http://localhost:8080` and log in as `alice` / `password`. See
[Getting Started](https://jwcarman.github.io/who/guides/getting-started/) for the
users and the API calls to try.

## Upgrading to 0.10.0

0.10.0 is a security release with breaking changes: the API-key filter is no longer
registered globally, method security is on by default, and existing databases need a
foreign key and revoked pending enrollment tokens. See
[Upgrading to 0.10.0](https://jwcarman.github.io/who/guides/upgrading/).

## Modules

| Module | What it is |
|--------|------------|
| `who-core` | Domain types (`Identity`, `WhoPrincipal`), service and SPI interfaces. Needs no Spring at runtime; `WhoService`'s `@Transactional` applies when Spring's transaction support is present (always, with who's starter) |
| `who-jdbc` | JDBC repositories, using Spring `JdbcClient` |
| `who-rbac` | `RbacService` and a `PermissionsResolver` backed by roles and permissions |
| `who-jwt` | `WhoJwtAuthenticationConverter` and `JwtCredential` |
| `who-apikey` | `ApiKeyAuthenticationFilter` and `ApiKeyService` |
| `who-enrollment` | `WhoEnrollmentService`, for issuing and redeeming enrollment tokens |
| `who-email-link` | Email-link sign-in: `EmailCredentialService`, `EmailLinkSender`, and the hooks for Spring's one-time-token login |
| `who-passkey` | Passkey sign-in: `PasskeyService`, `PasskeyAddedNotifier`, and the hooks for Spring's WebAuthn login |
| `who-spring-security` | `@RequiresPermission`, `WhoWebSecurity.passwordless()` and `GET /who/me` |
| `who-autoconfigure` | Spring Boot autoconfiguration for all modules |
| `who-spring-boot-starter` | Starter that pulls in all of the above |

## Building

```bash
# Compile and run tests
./mvnw clean verify

# Tests with code coverage
./mvnw clean verify -Pci

# Apply code formatting
./mvnw spotless:apply

# Build the docs site locally
pip install mkdocs-material==9.7.7
mkdocs serve
```

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
