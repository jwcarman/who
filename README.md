# Who

[![CI](https://github.com/jwcarman/who/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/who/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/who/main/pom.xml&query=//*[local-name()='java.version']/text()&label=Java&color=orange)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/org.jwcarman.who/who-spring-boot-starter)](https://central.sonatype.com/artifact/org.jwcarman.who/who-spring-boot-starter)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/jwcarman/who/badge)](https://scorecard.dev/viewer/?uri=github.com/jwcarman/who)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/16671/badge)](https://www.bestpractices.dev/projects/16671)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_who&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_who)

Identity, sign-in and permissions for Spring Boot applications. Who signs people in
without passwords (by email, with a link or a typed code, by passkey, and with Google, GitHub or any OpenID Connect provider, with a session), and maps every credential a
caller presents (a session, a JWT, an API key) to one stable internal identity. It resolves
that identity's permissions and lets your controllers check permission strings without
knowing which credential the caller used. Who formats no errors of its own: it throws, and
your application answers in its own format.

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
    <version>0.19.0</version>
</dependency>
```

Add each sign-in method you use beside it (`who-email-link`, `who-passkey`, `who-oidc`,
`who-apikey`, `who-jwt`); its jar turns it on. With no `SecurityFilterChain` of your own, who's default chain
mounts them all. See [Getting Started](https://jwcarman.github.io/who/guides/getting-started/).

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

Method security is on by default. Set up the credentials you accept with the
[JWT](https://jwcarman.github.io/who/guides/jwt/) and
[API Keys](https://jwcarman.github.io/who/guides/api-keys/) guides, and link a
user's credential to an identity with
[Enrollment](https://jwcarman.github.io/who/guides/enrollment/).

### Try the examples

[`who-examples/web-app`](who-examples/web-app) is a server-rendered app that signs people in
with email links, passkeys and an identity provider on who's default chain. With Docker running,
Spring Boot starts its Postgres, Mailpit and Keycloak for you:

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-examples/web-app
```

Its [README](who-examples/web-app/README.md) says what to try.
[`who-examples/spa`](who-examples/spa) is the same app as a React single-page app over a JSON
backend, started the same way (`-pl who-examples/spa`); its [README](who-examples/spa/README.md)
covers Vite's dev server. [`who-examples/api`](who-examples/api) is the board as a stateless JSON
API, called with Keycloak access tokens and who API keys (`-pl who-examples/api`); its
[README](who-examples/api/README.md) walks through it with `curl`.
[`who-examples/web-and-api`](who-examples/web-and-api) is one app that is both: a website with
sessions and an API with tokens and keys, each with a security chain of its own
(`WhoSecurity.stateless()` for the API).
[`who-examples/observability`](who-examples/observability) shows who's meters, traces and logs in
Grafana's LGTM stack, with a provisioned dashboard and a traffic generator
(`-pl who-examples/observability`).

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
| `who-federated` | `FederatedCredential`, the `(issuer, subject)` credential that JWT and provider sign-in share, and `FederatedCredentialService` |
| `who-jwt` | `WhoJwtAuthenticationConverter` |
| `who-oidc` | Sign-in with Google, GitHub or any OpenID Connect provider, on Spring's `oauth2Login`: `WhoSignUpPolicy` decides who may sign up |
| `who-apikey` | `ApiKeyAuthenticationFilter` and `ApiKeyService` |
| `who-enrollment` | `WhoEnrollmentService`, for issuing and redeeming enrollment tokens |
| `who-email-link` | Email sign-in with a link or a typed code: `EmailCredentialService`, `EmailLinkSender`, `EmailLinkMessage`, and the hooks for Spring's one-time-token login |
| `who-passkey` | Passkey sign-in: `PasskeyService`, `PasskeyAddedNotifier`, and the hooks for Spring's WebAuthn login |
| `who-spring-security` | `@RequiresPermission`, `WhoSecurity.all()`, `WhoWebSecurity.passwordless()` and `GET /who/me` |
| `who-autoconfigure` | Spring Boot autoconfiguration for all modules |
| `who-spring-boot-starter` | Starter with the base: `who-core`, `who-spring-security`, `who-jdbc`, `who-rbac` and the autoconfiguration. Add each sign-in module you use; its jar turns it on |
| `who-client` | `@jwcarman/who-client` on npm: a typed TypeScript client for the browser endpoints. See [TypeScript Client](https://jwcarman.github.io/who/guides/client/) |

## Building

```bash
# Compile and run tests
./mvnw clean verify

# Tests with code coverage
./mvnw clean verify -Pci

# Mutation testing (not part of CI; run it now and then to check test quality).
# Reports: <module>/target/pit-reports/index.html
./mvnw clean verify -Ppit

# Apply code formatting
./mvnw spotless:apply

# Build the docs site locally
pip install --require-hashes -r docs/requirements.txt
mkdocs serve
```

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
