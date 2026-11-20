# Getting Started

**Contents**

- Requirements
- Run the example app
- Add the dependency
- Secure the app
- Create an identity
- Deleting an identity
- Next steps

## Requirements

- Java 25+
- Spring Boot 4.1+ (Who is built against 4.1.2)
- PostgreSQL 14+ required. It is the database Who is tested against.

## Run the example app

To see every browser sign-in method working together, run the
[web app example](https://github.com/jwcarman/who/tree/main/who-examples/web-app): with Docker
running, `./mvnw spring-boot:run -pl who-examples/web-app` starts it with its Postgres, Mailpit and
Keycloak. The [single-page app example](https://github.com/jwcarman/who/tree/main/who-examples/spa)
is the same app in React over a JSON backend.

The [API example](https://github.com/jwcarman/who/tree/main/who-examples/api) is the same board
as a stateless JSON API, called with access tokens from Keycloak and with who API keys. Clone the
repository and start it:

```bash
git clone https://github.com/jwcarman/who
cd who
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-examples/api
```

Its README gets a token with `curl` and walks through the calls, as `alice` (an admin), `bob` (a
member) and `carol` (who has a Keycloak account but no who identity, so her token gets `401`).

## Add the dependency

Import the BOM so that every who module uses the same version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.jwcarman.who</groupId>
            <artifactId>who-bom</artifactId>
            <version>0.25.0</version>
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
<!-- email-link sign-in sends email: who-email renders it, who-email-smtp delivers it -->
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-email</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-email-smtp</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-test</artifactId>
    <scope>test</scope>
</dependency>
<!-- who creates and upgrades its own tables with Liquibase -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-liquibase</artifactId>
</dependency>
```

The starter brings the base: identities, sessions, permissions and the autoconfiguration. who
migrates its own tables with Liquibase at startup, and fails startup without it; see
[Database Setup](database.md). Each sign-in method is its own module, and adding its jar turns it
on:

| Sign-in method | Module | Guide |
|---|---|---|
| Emailed link or typed code | `who-email-link`, with `who-email` and a sender | [Email Links](email-links.md), [Email](email.md) |
| Passkey | `who-passkey` | [Passkeys](passkeys.md) |
| API key | `who-apikey` | [API Keys](api-keys.md) |
| JWT from your authorization server | `who-jwt` | [JWT](jwt.md) |
| Google, GitHub or any OpenID Connect provider | `who-oidc` | [Social Login](social-login.md) |

`who-enrollment` adds [enrollment tokens](enrollment.md), and `who-admin` [endpoints for managing identities, roles and invitations](admin.md). The BOM manages only who's own
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
- `GET /who/sign-in`, which tells a sign-in page which methods are on;
- each sign-in method's endpoints, such as `/who/email/send` and `/who/email/sign-in`;
- Actuator's health endpoint, if Actuator is present;
- the paths you list in `who.security.public-paths`, and those any `WhoPublicPathContributor` bean
  adds (see [Configuration](configuration.md)).

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
CSRF with Spring's `csrf.spa()`. A request with a bearer token or an API key and no session is
exempt: the token or key signs it in or it gets `401`. A request with a session ignores the token
or key and keeps its CSRF check. With only API keys or JWTs, who gives no request
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

### A website and an API

The most common reason for chains of your own is an app that is both a website for people and an
API for machines. The website needs sessions and CSRF; the API needs neither, and must not accept
a browser's session cookie. Give each its own chain, the API's first:

```java
@Bean
@Order(1)
SecurityFilterChain api(HttpSecurity http) {
    http.securityMatcher("/api/**")
        .with(WhoSecurity.stateless(), Customizer.withDefaults())
        .authorizeHttpRequests(a -> a.anyRequest().authenticated());
    return http.build();
}

@Bean
@Order(2)
SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults())
        .authorizeHttpRequests(a -> a
            .requestMatchers(publicPaths.patterns()).permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

`WhoSecurity.stateless()` mounts only who's token and key sign-in (`who-jwt`, `who-apikey`), never
a browser method, into a chain that:

- never reads or creates a session, so a session cookie, who's or Spring's own, signs nothing in
  on the API and a signed-out call starts none;
- has no CSRF protection, because nothing it accepts rides along with a request by itself: a
  forged cross-site request cannot add the header a token or key travels in;
- answers a signed-out request with `401` and a denied one with `403`, through your
  `@RestControllerAdvice` like every who error; the `401` carries `WWW-Authenticate: Bearer` when
  JWTs are on.

With neither `who-jwt` nor `who-apikey` on, startup fails. The
[web-and-api example](https://github.com/jwcarman/who/tree/main/who-examples/web-and-api) is this
app, runnable.

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

1. [Database Setup](database.md): how who migrates its tables, and running its changelogs yourself.
2. [Email Links](email-links.md), [Passkeys](passkeys.md), [API Keys](api-keys.md) or
   [JWT](jwt.md): set up each sign-in method you added.
3. [Enrollment](enrollment.md): link a user's credential to an identity.
4. [RBAC and Method Security](rbac.md): grant permissions and check them.
