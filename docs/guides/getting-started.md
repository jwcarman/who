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

Import the BOM so that every who module uses the same version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-bom</artifactId>
            <version>0.12.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add the starter, and `who-test` for your tests, with no versions:

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-test</artifactId>
    <scope>test</scope>
</dependency>
```

The starter brings every module and the autoconfiguration. The BOM manages only who's own artifacts, not Spring Boot's versions.

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

## Deleting an identity

Delete identities through `WhoService` too:

```java
boolean deleted = whoService.deleteIdentity(identityId); // false if there is no such identity
```

In one transaction, it deletes each of the identity's email, JWT and API key
credentials, unlinks them, and deletes the identity. Its role assignments,
enrollment tokens and passkeys go with it.

Do not run `DELETE FROM who_identity` yourself. Credential rows do not reference
their identity, so a raw delete removes only the links and leaves the credential
rows behind. `EmailCredentialService.register` lets the next identity register
such an orphaned address: it deletes the old credential, with any sign-in link
sent to the deleted user, and registers a new one. To clean them up, call:

```java
int removed = whoService.purgeOrphanedCredentials();
```

It deletes every credential linked to no identity, including credentials that
are unlinked only for now. A JWT credential saved for [enrollment](enrollment.md)
is unlinked until the user enrolls, and a credential being registered (an API
key, say) is unlinked between being saved and being linked. Run the purge when
no enrollment or registration is in flight.

If you replace one of who's credential repository beans, `deleteIdentity` and
the purge skip that module's rows unless your bean also implements
`CredentialRemover` (and is declared with a type that includes it).

## Next steps

1. [Database Setup](database.md): create Who's tables.
2. [JWT](jwt.md) or [API Keys](api-keys.md): accept credentials.
3. [Enrollment](enrollment.md): link a user's credential to an identity.
4. [RBAC and Method Security](rbac.md): grant permissions and check them.
