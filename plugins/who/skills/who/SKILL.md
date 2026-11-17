---
name: who
description: Sets up and uses the who library (org.jwcarman.who) for identity, sign-in and permissions in a Spring Boot 4 app - passwordless email links and codes, passkeys, Google/GitHub/OpenID Connect sign-in, API keys, JWTs, roles and @RequiresPermission, sessions, the Cloudflare Turnstile human check, and the @jwcarman/who-client TypeScript client. Use this skill whenever the project depends on who (who-spring-boot-starter, who-bom or any org.jwcarman.who artifact, WhoService, WhoPrincipal, /who/ endpoints, who.* properties), or the user asks to add or change sign-in, login, magic links, passkeys, social login, API keys, permissions or a captcha in an app that uses who, or asks how to do anything with who - even when they don't say "who" but the code clearly uses it.
---

# who

who gives a Spring Boot app identities, passwordless sign-in and permissions. Every credential a
caller presents (a browser session, a JWT, an API key) maps to one stable internal identity, and
controllers check permission strings without caring which credential was used. who formats no
errors: it throws, and the application answers in its own format.

Requirements: Java 25+, Spring Boot 4.1+, PostgreSQL 14+ (the only database who is tested on).

The `references/` folder holds who's own guides and changelog, copied from the repository for
this version of the skill. They are the source of truth: read the guide for the module you are
touching before writing code, and take property names and APIs from it rather than from memory.
who is young and its API has changed between releases, so guesses from general Spring knowledge
are often wrong in the details.

## Which who these guides describe

The guides are copied from who's main branch, which is usually ahead of the latest release on
Maven Central, so they can describe modules, properties and log lines a released who does not
have yet. `references/CHANGELOG.md`, copied with them, says what arrived when: released versions
are headed `## [x.y.z]`, and `## [Unreleased]` is on main only. Its links to `docs/guides/<name>.md`
are the `references/<name>.md` files beside it.

Before using anything, find the project's who version (the `who-bom` import), or for a new project
the latest release of `org.jwcarman.who:who-bom` on Maven Central. Then look in the changelog:
anything listed under `Unreleased` or under a version newer than the project's does not exist
there. Don't use it; tell the user it arrives in a later version. If the
project's who is older than the guides, also read `references/upgrading.md` and point out what
differs, rather than mixing two versions' APIs.

## Start by looking at the project

Before changing anything, find out what is already there:

- The build file: is `who-bom` imported, which version, which `who-*` modules are present?
- `application.yml` / `application.properties`: which `who.*` settings exist.
- Any `SecurityFilterChain` bean. Its presence changes how who is wired (see below).
- How the schema is managed (Flyway, Liquibase, or nothing yet).

## Adding who

1. Import the BOM, then add the starter and each sign-in module with no versions. Add
   `who-test` with test scope. The BOM manages only who's artifacts, not Spring Boot's.

   ```xml
   <dependencyManagement>
     <dependencies>
       <dependency>
         <groupId>org.jwcarman.who</groupId>
         <artifactId>who-bom</artifactId>
         <version>${who.version}</version>
         <type>pom</type>
         <scope>import</scope>
       </dependency>
     </dependencies>
   </dependencyManagement>
   ```

2. Pick modules from what the user wants. Adding a module's jar turns it on; a property turns it
   off without removing the jar (`who.email-link.enabled`, `who.passkey.enabled`,
   `who.api-key.enabled`, `who.jwt.enabled`, `who.oidc.enabled`, `who.turnstile.enabled`,
   `who.enrollment.enabled`, `who.admin.enabled`; the names do not always match the artifact's, so take them from
   `references/configuration.md`).

   | The user wants | Module | Read |
   |---|---|---|
   | Sign in by emailed link or typed code ("magic link", "passwordless") | `who-email-link` | `references/email-links.md` |
   | Passkeys / WebAuthn | `who-passkey` | `references/passkeys.md` |
   | Google, GitHub, Keycloak, Auth0 or any OpenID Connect provider | `who-oidc` | `references/social-login.md` |
   | Machine clients with API keys | `who-apikey` | `references/api-keys.md` |
   | Calls carrying JWTs from an authorization server | `who-jwt`, usually with `who-enrollment` | `references/jwt.md`, `references/enrollment.md` |
   | A bot check on the email endpoints | `who-turnstile` | `references/email-links.md` (Human check) |
   | Roles and permissions | in the starter | `references/rbac.md` |
   | An admin screen: list identities, deactivate them, assign roles, invite by email | `who-admin` | `references/admin.md` |
   | A profile page: the person's passkeys, addresses, linked accounts, API keys, sessions | (each sign-in module serves its own, under `/who/me`) | `references/account.md` |
   | A browser or SPA client in TypeScript | `@jwcarman/who-client` (npm) | `references/client.md` |

   who never creates an identity for a JWT it has not seen: a token is refused until its
   `(issuer, subject)` is linked to an identity, which is what `who-enrollment`'s tokens are for.

3. Create the tables. `who.initialize-schema: always` is fine for local development. For
   production use `never` and copy who's schema into Flyway or Liquibase, in the order
   `references/database.md` gives (the `who-jdbc` schema first; others reference its tables).

4. Set the module's required properties. Every module that needs settings refuses to start
   without them and says what to set, so a startup failure naming a `who.*` property is who
   working as designed: set the property, never work around the check. The ones people hit
   first:

   | Module | Required |
   |---|---|
   | `who-email-link` | `who.email-link.link-base-url` (your sign-in page), `who.email-link.code-secret` (32+ chars, the same on every instance, from a secret store), and a way to send mail: an `WhoEmailSender` bean, or `spring-boot-starter-mail` (who-email-link does not bring it) with `spring.mail.host` and `who.email-link.from` |
   | `who-passkey` | `who.passkey.rp-id` (the app's own host, e.g. `localhost` in development), and `who.passkey.allowed-origins` whenever the browser does not reach the app at `https://<rp-id>` exactly, such as `http://localhost:8080` or https on another port |
   | `who-turnstile` | `who.turnstile.secret-key` |
   | `who-jwt` | Spring's resource-server settings, including `spring.security.oauth2.resourceserver.jwt.audiences` |

   `references/configuration.md` lists every property with its default.

5. Open your own pages to signed-out visitors. With no `SecurityFilterChain` of your own, who's
   chain answers every signed-out request with `401` except `/error`, the sign-in endpoints,
   Actuator health and `who.security.public-paths`. The sign-in page (and the page
   `link-base-url` points at) and its assets must be listed there, or the user can never reach
   them.

6. Verify: build, start the app against Postgres, and check that `GET /who/me` answers `401`
   signed out. Recent versions also log, at startup, one line per kind of chain naming what it
   mounted (`who: this chain signs browsers in with ...`, `who: this chain accepts api-key, jwt`);
   see `references/observability.md` for whether the project's version has them.

## Your own security chains

Let who's default chain do the work unless there is a reason not to. Defining any
`SecurityFilterChain` makes who's back off (Spring Boot's rule), and then everything the default
chain did is yours to do.

who's configurers mount sign-in only. **They do not authorize requests**: a chain without its own
`authorizeHttpRequests` lets signed-out callers through. Every chain you write needs it, and a
browser chain also opens who's public paths from the injected `WhoPublicPaths` bean. Actuator
health is not among those paths; permit it yourself if the chain covers it.

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

`WhoSecurity.all()` mounts every sign-in method on the classpath. To mount one at a time:
`WhoWebSecurity.passwordless()` for email links and passkeys, `WhoApiKeySecurity.apiKey()` for
API keys, and Spring's `oauth2ResourceServer()` with `WhoJwtAuthenticationConverter` for JWTs.
Hand-wiring Spring's `oneTimeTokenLogin()` or `webAuthn()` yourself skips who's identity mapping
and checks.

**A website plus an API means two chains**, the API's first. The API must not accept a browser's
session cookie: on one shared chain, a request that carries a session is signed in by it, even
when it also carries an API key. `WhoSecurity.stateless()` mounts only token and key sign-in,
never reads or creates a session, and needs no CSRF protection:

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

## Rules worth knowing (and why)

- **Create and delete identities through `WhoService`** (`createIdentity()`,
  `deleteIdentity(id)`), never through repositories or SQL: a raw delete leaves credential rows
  behind that another identity could then claim.
- **Check permissions with `@RequiresPermission("task.read")`** and get the caller with
  `@AuthenticationPrincipal WhoPrincipal`. Method security is on by default. Grant permissions
  through roles with `RbacService`.
- **Know what a new credential can reach.** A chain's `anyRequest().authenticated()` lets any
  signed-in caller through, and only `@RequiresPermission` narrows that. So when you add a way in
  (an API key for CI, a JWT for a partner), look at every endpoint it can now call, not just the
  one the user named: an endpoint with no permission check is open to that credential too. Tell
  the user which ones, and offer to guard them, rather than deciding silently.
- **Use who's own account and admin endpoints before writing new ones.** Everything under
  `/who/me` (sessions, passkeys, email addresses, linked accounts, API keys) and who-admin's
  `/who/identities`, `/who/roles` and `/who/invitations` check ownership, recent sign-in and the
  last-credential rule. The services behind them (`PasskeyService.revoke`,
  `EmailCredentialService.revoke`, `FederatedCredentialService.unlink`, `ApiKeyService.revoke`)
  check none of that, so an endpoint of your own over them must. Call
  `InvitationService.invite` outside any transaction, and attach the app's own data from an
  `IdentityInvited` listener. See `references/account.md` and `references/admin.md`.
- **Answer who's errors in the app's own format.** who throws exceptions (such as
  `NotSignedInException`) and hands even filter-level ones to Spring MVC's exception resolvers,
  so one `@RestControllerAdvice` covers them. Each also sets a `Who-Error` header with a stable
  code that clients can rely on. See `references/errors.md`.
- **Browser sign-in uses Spring's `csrf.spa()`**: the client reads the `XSRF-TOKEN` cookie and
  sends `X-XSRF-TOKEN` on unsafe requests. The TypeScript client does this for you.
- **Secrets come from the environment**, never from committed config, and never with a default
  value in the placeholder: `code-secret`, Turnstile's secret key, OAuth client secrets. Spring
  binds an unresolved `${VAR}` as literal text; who refuses that for its own secrets
  (`code-secret`, Turnstile's key) by failing startup, but Spring's OAuth client secret gets no
  such check.
- **Cloudflare's Turnstile test keys are for local development only.** With a test secret key,
  who passes a request on Cloudflare's success alone, skipping the action and hostname checks, and
  warns at startup; the always-pass key then lets everyone through. A test key never accepts a
  real token. Do not put one as the default for a production variable.
- **Rate-limit sign-in at the ingress** (load balancer, WAF, nginx). who limits code guesses per
  address but does not limit by client IP; `references/email-links.md` has an nginx example.
- **Passkeys need an exact host and origin.** `rp-id` is the app's own host, not a shared parent
  domain, and the browser must reach the app at one of `allowed-origins`.

## Testing

Add `who-test` and use `WhoMockMvcBrowser`: it keeps the session and cookies like a browser,
sends the CSRF header, and `signedInAs(identityId, credentialId)` signs a user in without an
email. The identity and credential must really exist in the test database (create them with
`WhoService` and the credential service), because who checks them on every request. Run tests
against PostgreSQL, for example with Testcontainers. Passkey ceremonies cannot be emulated; sign
the user in and test what comes after. See `references/testing.md`.

## Which reference to read

Read the file for the task before writing code. Long files start with a contents list; read the
sections you need.

- `getting-started.md`: adding who to a project, the default chain, chains of your own, creating
  and deleting identities.
- `configuration.md`: the exact name and default of any `who.*` property, and every endpoint path.
- `database.md`: creating who's tables, Flyway or Liquibase copies of the schema.
- `email-links.md`: sign-in by emailed link or code, sending mail, adding or changing an address,
  the Turnstile human check, rate limiting.
- `passkeys.md`: passkey sign-in and registration, `rp-id` and origins, the "passkey added" email.
- `social-login.md`: Google, GitHub or any OpenID Connect provider, who may sign up, linking
  accounts.
- `api-keys.md`: issuing, rotating and revoking API keys, scopes, the key header.
- `jwt.md`: accepting JWTs from an authorization server.
- `enrollment.md`: linking a JWT or provider credential to an identity with enrollment tokens.
- `rbac.md`: roles, permissions, `@RequiresPermission`.
- `security-model.md`: what each check protects against, recent sign-in and
  `@RequiresRecentSignIn`.
- `sessions.md`: listing and ending sessions, sign-out, the last-sign-in cookie, purging old
  session records.
- `account.md`: everything under `/who/me` and `GET /who/sign-in`: who may call each endpoint,
  the response shapes, the last-credential rule.
- `admin.md`: who-admin's identity, role and invitation endpoints, their permissions, and
  `InvitationService`.
- `errors.md`: who's exceptions, the `Who-Error` codes, answering them in the app's format.
- `client.md`: the `@jwcarman/who-client` TypeScript client for browser pages.
- `testing.md`: testing an app built on who with `who-test`.
- `events.md`: reacting to sign-ins and other security events.
- `observability.md`: metrics, traces, the startup log lines, alerts.
- `native.md`: GraalVM native images.
- `upgrading.md`: moving a project from one who version to another.
- `CHANGELOG.md`: what changed in which version; check it before using anything, as above.

The published site is https://jwcarman.github.io/who/ and the API reference
https://jwcarman.github.io/who/api/latest/.
