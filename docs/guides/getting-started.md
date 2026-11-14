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
            <version>0.17.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add the starter, each sign-in method you use, and `who-test` for your tests, with no
versions:

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-email-link</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-test</artifactId>
    <scope>test</scope>
</dependency>
```

The starter brings the base: identities, sessions, permissions and the autoconfiguration. Each
sign-in method is its own module, and adding its jar turns it on:

| Sign-in method | Module | Guide |
|---|---|---|
| Emailed link or typed code | `who-email-link` | [Email Links](email-links.md) |
| Passkey | `who-passkey` | [Passkeys](passkeys.md) |
| API key | `who-apikey` | [API Keys](api-keys.md) |
| JWT from your authorization server | `who-jwt` | [JWT](jwt.md) |

`who-enrollment` adds [enrollment tokens](enrollment.md). The BOM manages only who's own
artifacts, not Spring Boot's versions.

A module that needs settings fails startup until it has them, and the report says what to set,
for example:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

who cannot start: who.email-link.link-base-url must be set to the page that receives sign-in links.

Action:

Set it to the URL of your sign-in page, such as https://app.example.com/signin. Or turn email-link sign-in off with who.email-link.enabled=false.
```

`who.<module>.enabled=false` turns a module off without removing its jar. See the
[Configuration Reference](configuration.md).

## Secure the app

With no `SecurityFilterChain` of your own, who registers one in place of each default chain
Spring Boot would register (form login, the resource server's, Actuator's, `oauth2Login`'s). It mounts
every sign-in method on the classpath and answers a signed-out request with `401` (as a
`NotSignedInException`, so your `@RestControllerAdvice` can shape it; see [Errors](errors.md)).
There is no login page and no redirect. Signed-out requests may reach only:

- `/error`;
- each sign-in method's endpoints, such as `/ott/generate` and `/login/ott`;
- Actuator's health endpoint, if Actuator is present;
- the paths you list in `who.security.public-paths`.

List your own sign-in page and the assets it loads there, including the page
`who.email-link.link-base-url` points at:

```yaml
who:
  security:
    public-paths:
      - /signin
      - /assets/**
```

When a browser sign-in method (email link or passkey) is present, the chain protects against
CSRF with Spring's `csrf.spa()`. A request with a bearer token is exempt: the token signs it in
or it gets `401`. A request with an API key is exempt when it has no session; a request with a
session ignores the key and keeps its CSRF check. With only API keys or JWTs, who gives no request
a session, so the chain checks CSRF only on requests that bring one, and a signed-out client can
post to a public path.

### Your own chain

Define any `SecurityFilterChain` and who's chain backs off; Spring Boot's rule. To keep who's
sign-in methods in a chain of your own, mount them all with `WhoSecurity.all()`, and open the same
paths with `WhoPublicPaths`:

```java
@Bean
SecurityFilterChain chain(HttpSecurity http, WhoPublicPaths publicPaths) {
    http.with(WhoSecurity.all(), Customizer.withDefaults())
        .authorizeHttpRequests(a -> a
            .requestMatchers(publicPaths.patterns()).permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

Or wire one method at a time, as each method's guide shows: `WhoWebSecurity.passwordless()` for
email links and passkeys, `WhoApiKeySecurity.apiKey()` for API keys, and Spring's
`oauth2ResourceServer()` with `WhoJwtAuthenticationConverter` for JWTs.

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
2. [Email Links](email-links.md), [Passkeys](passkeys.md), [API Keys](api-keys.md) or
   [JWT](jwt.md): set up each sign-in method you added.
3. [Enrollment](enrollment.md): link a user's credential to an identity.
4. [RBAC and Method Security](rbac.md): grant permissions and check them.
