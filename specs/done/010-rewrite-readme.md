# Rewrite README to reflect current library state

## What to build

Replace the current `README.md` with an accurate description of what the library
actually does today. The existing README describes a much larger aspirational
library (invitations, contact methods, preferences, who-web, who-security) — none
of which exists. Strip all of that out and document what is real.

### Structure

1. **Header** — badges (keep existing), one-line description
2. **Quick Start — Run the Example App** — keep existing section (it is accurate)
3. **How it works** — the pipeline in one paragraph:
   `HttpRequest → CredentialExtractor → Credential → Identity → Set<String> permissions → WhoPrincipal`
   Explain that `WhoPrincipal` ends up in the Spring Security context and the app
   uses `@PreAuthorize` with permission strings.
4. **Add the dependency** — Maven snippet for `who-spring-boot-starter`
5. **Database setup** — three sub-sections (see below)
6. **Configure JWT** — `application.yml` snippet for `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`
   (or `issuer-uri`), and wire `WhoJwtAuthenticationConverter` into the resource server
   security filter chain
7. **Enroll credentials** — briefly explain that a `JwtCredential` row must exist for each
   `(issuer, subject)` pair before access is granted; point to the enrollment API (spec 007)
   or manual SQL insert
8. **RBAC** — show how to create roles, assign permissions, and assign roles to identities
   via `RbacService`; show `@PreAuthorize("hasAuthority('task.read')")` usage
9. **Modules** — accurate table of what exists: `who-core`, `who-jdbc`, `who-rbac`,
   `who-jwt`, `who-autoconfigure`, `who-spring-boot-starter`
10. **Build** — keep existing section

### Database setup section

This section has three sub-sections:

#### Option A: Spring Boot `spring.sql.init`

Explain that each Who module ships its own `schema.sql` on the classpath. List the
files by module and show how to reference them:

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations:
        - classpath:org/jwcarman/who/jdbc/schema.sql   # who-jdbc: who_identity, who_credential_identity
        - classpath:org/jwcarman/who/rbac/schema.sql   # who-rbac: who_role, who_role_permission, who_identity_role
        - classpath:org/jwcarman/who/jwt/schema.sql    # who-jwt:  who_jwt_credential
```

Note that `spring.sql.init` is suitable for development/testing. For production,
use Flyway or Liquibase.

#### Option B: Flyway

Show a single migration file (`V1__who.sql`) that the user copies into their
`db/migration` directory, containing the full DDL for all tables they need.
Include the actual DDL (from the schema files above) so they can copy-paste it.

#### Option C: Liquibase

Show an equivalent `db/changelog/001-who.yaml` changeset covering the same tables.

## Acceptance criteria

- [ ] README accurately describes only what is implemented today — no aspirational features
- [ ] README includes the `spring.sql.init` example with all three classpath schema locations
- [ ] README includes a Flyway migration example with the full DDL
- [ ] README includes a Liquibase changelog example with the full DDL
- [ ] README shows how to wire `WhoJwtAuthenticationConverter` into a resource server filter chain
- [ ] README shows a `@PreAuthorize` example
- [ ] README modules table lists only modules that exist
- [ ] `progress.txt` updated

## Implementation notes

- Do not add any new code — this is documentation only
- The Flyway and Liquibase examples should be complete enough to copy-paste, not sketches
- Keep the Quick Start section at the top unchanged — it is accurate and useful
