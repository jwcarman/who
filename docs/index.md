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

-   **API keys**

    ---

    Keys are hashed before storage and shown once. Add them to the filter chains
    that need them. See [API Keys](guides/api-keys.md).

-   **Enrollment**

    ---

    Single-use, expiring tokens that link a user's credential to an identity you
    created. See [Enrollment](guides/enrollment.md).

-   **Roles and permissions**

    ---

    `RbacService` manages roles. `@RequiresPermission` is enforced by default.
    See [RBAC and Method Security](guides/rbac.md).

</div>

## Modules

| Module | What it is |
|--------|------------|
| `who-core` | Domain types (`Identity`, `WhoPrincipal`), service and SPI interfaces. No Spring dependency |
| `who-jdbc` | JDBC repositories, using Spring `JdbcClient` |
| `who-rbac` | `RbacService` and a `PermissionsResolver` backed by roles and permissions |
| `who-jwt` | `WhoJwtAuthenticationConverter` and `JwtCredential` |
| `who-apikey` | `ApiKeyAuthenticationFilter` and `ApiKeyService` |
| `who-enrollment` | `WhoEnrollmentService`, for issuing and redeeming enrollment tokens |
| `who-spring-security` | `@RequiresPermission` |
| `who-autoconfigure` | Spring Boot autoconfiguration for all modules |
| `who-spring-boot-starter` | Starter that pulls in all of the above |
