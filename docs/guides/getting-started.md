# Getting Started

## Requirements

- Java 25+
- Spring Boot 4.1+ (Who is built against 4.1.2)
- PostgreSQL 14+ required. It is the database Who is tested against.

## Run the example app

Clone the repository and make authenticated API calls in a few minutes:

```bash
git clone https://github.com/jwcarman/who
cd who
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-example
```

Then:

1. Open `http://localhost:8080` in a browser.
2. Click **Login** and log in as `alice` / `password`.
3. Copy the access token shown on the page. The demo shows it for convenience;
   real apps never display tokens.
4. Use it:

```bash
curl http://localhost:8080/api/me \
  -H "Authorization: Bearer <paste-token-here>"

curl http://localhost:8080/api/tasks \
  -H "Authorization: Bearer <paste-token-here>"
```

Pre-configured users:

| Username | Password | Permissions |
|----------|----------|-------------|
| alice | password | `task.read` |
| bob | password | `task.read`, `task.write` |
| admin | password | `task.read`, `task.write`, `task.delete` |

## Add the dependency

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-spring-boot-starter</artifactId>
    <version>0.10.0</version>
</dependency>
```

The starter brings every module and the autoconfiguration.

## Create an identity

`WhoService` is the entry point for creating identities. Do not write to
`IdentityRepository` directly.

```java
@Autowired WhoService whoService;

// A new ACTIVE identity with a generated UUIDv7
Identity identity = whoService.createIdentity();
UUID identityId = identity.id();
```

Everything else (enrolling credentials, assigning roles, issuing API keys) takes
an identity created this way.

## Next steps

1. [Database Setup](database.md): create Who's tables.
2. [JWT](jwt.md) or [API Keys](api-keys.md): accept credentials.
3. [Enrollment](enrollment.md): link a user's credential to an identity.
4. [RBAC and Method Security](rbac.md): grant permissions and check them.
