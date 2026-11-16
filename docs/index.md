---
hide:
  - navigation
  - toc
---

<div class="jw-hero" markdown>

# Who

<p class="jw-hero__tagline">Identity and permissions for Spring Boot applications.</p>

Java 25+ · Spring Boot 4.1+ · PostgreSQL 14+

<p class="jw-hero__actions">
  <a class="md-button md-button--primary" href="guides/getting-started/">Get started</a>
  <a class="md-button" href="https://github.com/jwcarman/who">View on GitHub</a>
</p>

</div>

Who maps the credentials a caller presents (a JWT, an API key) to one stable
internal identity, and resolves that identity's permissions. Your controllers
check permission strings. They do not know which credential the caller used.

```java
@GetMapping("/tasks")
@RequiresPermission("task.read")
public List<Task> getTasks(@AuthenticationPrincipal WhoPrincipal principal) {
    return taskService.findAll(principal.identity().id());
}
```

## How it works

Every request goes through this pipeline:

```
HttpRequest → Credential → Identity → Set<String> permissions → WhoPrincipal
```

- A **credential** is something a caller presents: the `(iss, sub)` pair of a
  validated JWT, or an API key. Who stores credentials and links each one to an
  identity.
- An **identity** is a UUID with a status. One identity can have many
  credentials. Only `ACTIVE` identities authenticate.
- A **permissions resolver** loads the identity's permission strings. With
  `who-rbac`, permissions come from the roles assigned to the identity.
- The resulting `WhoPrincipal` goes into the Spring Security context, and
  `@RequiresPermission` checks it.

Who does not auto-provision. A credential that is not linked to an identity is
denied. You link credentials with an [enrollment](guides/enrollment.md) flow, or
by creating them yourself.

## What you get

<div class="grid cards" markdown>

-   **JWT resource servers**

    ---

    `WhoJwtAuthenticationConverter` turns a validated JWT into a `WhoPrincipal`.
    See [JWT](guides/jwt.md).

-   **Social login**

    ---

    Sign in with Google, GitHub or any OpenID Connect provider. Sign-up is
    closed until your policy opens it, and nothing links by email. See
    [Social Login](guides/social-login.md).

-   **API keys**

    ---

    Keys expire, hold only the permissions they were scoped to, record their
    last use, and rotate with an overlap. They are hashed before storage and
    shown once. See [API Keys](guides/api-keys.md).

-   **Enrollment**

    ---

    Single-use, expiring tokens that link a user's credential to an identity you
    created. See [Enrollment](guides/enrollment.md).

-   **Roles and permissions**

    ---

    `RbacService` manages roles. `@RequiresPermission` is enforced by default.
    See [RBAC and Method Security](guides/rbac.md).

</div>

## Examples

Runnable apps, one for each kind of app built on who, in
[`who-examples`](https://github.com/jwcarman/who/tree/main/who-examples). Each starts what it needs
with Spring Boot's Docker Compose support.

- [Web app](https://github.com/jwcarman/who/tree/main/who-examples/web-app): server-rendered
  pages with email links, passkeys and social login on who's default chain.
- [Single-page app](https://github.com/jwcarman/who/tree/main/who-examples/spa): the same app in
  React and TypeScript over a JSON backend, built by Maven into the jar or run on Vite's dev server.
- [API](https://github.com/jwcarman/who/tree/main/who-examples/api): the board as a stateless JSON
  API, called with access tokens from Keycloak and with API keys, which people make and revoke
  through it.
- [Website and API](https://github.com/jwcarman/who/tree/main/who-examples/web-and-api): one app
  that is both, with a security chain for each: sessions and CSRF for the pages,
  `WhoSecurity.stateless()` for `/api/**`. People manage their API keys on the website.

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
