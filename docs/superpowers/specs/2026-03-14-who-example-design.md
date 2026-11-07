Status: implemented
Original: specs/done/006-who-example.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

# who-example: self-contained example application

## What to build

Create the `who-example` Maven module — a fully self-contained Spring Boot application
that demonstrates the Who library end-to-end. It bundles an embedded Spring Authorization
Server so there are zero external dependencies. Someone can clone the repo and be making
authenticated API calls in under two minutes.

### Module setup

New Maven module `who-example` with dependencies:
- `who-spring-boot-starter`
- `spring-boot-starter-web`
- `spring-boot-starter-security`
- `spring-boot-starter-oauth2-authorization-server`
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-oauth2-client` (the UI logs in through the embedded server)
- `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6`
- `spring-boot-starter-jdbc`
- H2 (runtime scope)
- `spring-boot-starter-test`, `spring-security-test` (test scope)

It is listed in the root `pom.xml` `<modules>` but is not published to Maven Central: the
release workflow (`.github/workflows/maven-publish.yml`) deploys with `-pl '!who-example'`.

### Embedded Authorization Server

Spring Authorization Server runs on the same port (8080) as the API. Configure:

**Registered OAuth2 client** (in-memory, `client_secret_basic`):
```
client-id:     demo-client
client-secret: secret
grant-types:   authorization_code, refresh_token
redirect-uri:  http://localhost:8080/login/oauth2/code/demo-client
scopes:        openid, profile, email
```

**In-memory users** (BCrypt-encoded passwords):
| Username | Password | Description         |
|----------|----------|---------------------|
| alice    | password | viewer only         |
| bob      | password | can read and write  |
| admin    | password | full access         |

**Issuer:** `http://localhost:8080`. Keys: an RSA key pair generated at startup.

An `OAuth2TokenCustomizer` sets the username as the `sub` claim in issued JWTs. This makes
the bootstrap data predictable.

### Security configuration

Three security filter chains:

1. **Authorization server chain** (`@Order(1)`) — matches the authorization server's
   endpoints (`/oauth2/**`, `/.well-known/**`, OIDC `/userinfo`), with OIDC enabled.
   Unauthenticated HTML requests are sent to `/login`.

2. **API resource server chain** (`@Order(2)`) — handles `/api/**`. Stateless, JWT
   authentication using `WhoJwtAuthenticationConverter`. Requires authentication for
   all `/api/**` requests. CSRF disabled.

3. **Default chain** (`@Order(3)`) — handles everything else (the Thymeleaf UI). `/` and
   `/error` are public. Form login (for the authorization server) and OAuth2 login with
   `demo-client` (for the UI), both redirecting to `/` after login.

### Database bootstrap

Use H2 in-memory database (PostgreSQL compatibility mode). Initialize schema from the
Who module schema files plus the example's own schema:

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations:
        - classpath:org/jwcarman/who/jdbc/schema.sql
        - classpath:org/jwcarman/who/rbac/schema.sql
        - classpath:org/jwcarman/who/jwt/schema.sql
        - classpath:schema.sql
      data-locations:
        - classpath:data.sql
```

(Since the database is embedded, Who's own schema initializer also runs every module's
schema under the default `who.initialize-schema: embedded`; all scripts use
`CREATE TABLE IF NOT EXISTS`.)

**`src/main/resources/schema.sql`** — example domain table only:
```sql
CREATE TABLE IF NOT EXISTS task (
    id     UUID         PRIMARY KEY,
    title  VARCHAR(255) NOT NULL,
    status VARCHAR(20)  NOT NULL
);
```

**`src/main/resources/data.sql`** — bootstrap all Who data and sample tasks:

Pre-register identities, JWT credentials, and credential-identity links for all three
users with well-known UUIDs so the data is readable. Set `issuer = 'http://localhost:8080'`
and `subject = '<username>'` (alice, bob, admin).

Roles and permissions:
- `VIEWER` — `task.read`
- `EDITOR` — `task.read`, `task.write`
- `ADMIN`  — `task.read`, `task.write`, `task.delete`

Assignments:
- alice → VIEWER
- bob   → EDITOR
- admin → ADMIN

Sample tasks (3 tasks in various statuses).

### Example domain

**`Task`** — record: `id UUID`, `title String`, `status TaskStatus`

**`TaskStatus`** — enum: `OPEN`, `IN_PROGRESS`, `DONE`

**`TaskRepository`** — plain JDBC using `JdbcClient`:
```java
List<Task> findAll();
Optional<Task> findById(UUID id);
Task save(Task task);      // assigns a UUIDv7 id and OPEN status when missing
void deleteById(UUID id);
```

### API controllers

**`TaskController`** — base path `/api/tasks`:
```
GET    /api/tasks          requires task.read   → list all tasks
GET    /api/tasks/{id}     requires task.read   → get one task (404 if missing)
POST   /api/tasks          requires task.write  → create task (201)
DELETE /api/tasks/{id}     requires task.delete → delete task (204)
```

Use `@PreAuthorize("hasAuthority('task.read')")` etc. on each method.

**`MeController`** — `GET /api/me` (no special permission required, just authenticated):
Returns the current user's `WhoPrincipal` — their `identity` (id, status, timestamps) and
`permissions`. Useful for verifying the library is working correctly.

### Thymeleaf UI

Minimal — just enough to get a token into a developer's hands:

**`/`** — home page. If not logged in, shows a "Login" link (`/oauth2/authorization/demo-client`).
If logged in, shows:
- The user's username
- Their identity ID (`principal.identity().id()` from the `WhoPrincipal` resolved via
  `WhoService`, using the user's `JwtCredential`)
- Their permissions
- Their access token, under a heading saying it is shown for the demo only (real apps never
  display tokens; see `2026-11-07-security-fixes-design.md`, I6)
- Curl examples pre-filled with the token

### Documentation

The quick start lives in `docs/guides/getting-started.md` ("Run the example app"); the root
`README.md` has a short "Try the example app" section that links to it:

```bash
git clone https://github.com/jwcarman/who
cd who
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-example

# 1. Open http://localhost:8080 in a browser
# 2. Click Login and log in as alice / password
# 3. Copy the access token shown on the page
# 4. Use it:
curl http://localhost:8080/api/me \
  -H "Authorization: Bearer <paste-token-here>"

curl http://localhost:8080/api/tasks \
  -H "Authorization: Bearer <paste-token-here>"
```

## Acceptance criteria

- [ ] `who-example` module exists, builds, and starts successfully with `mvn spring-boot:run`
- [ ] H2 schema initializes without errors on startup (all Who schemas + example schema)
- [ ] Embedded OAuth2 authorization server issues JWTs at `http://localhost:8080/oauth2/token`
- [ ] Logging in as alice/bob/admin via the browser UI shows the correct identity ID and permissions
- [ ] `GET /api/me` with alice's token returns her identity and `[task.read]`
- [ ] `GET /api/me` with admin's token returns admin's identity and `[task.read, task.write, task.delete]`
- [ ] `GET /api/tasks` succeeds for alice (has `task.read`)
- [ ] `POST /api/tasks` returns 403 for alice (missing `task.write`)
- [ ] `POST /api/tasks` succeeds for bob (has `task.write`)
- [ ] `DELETE /api/tasks/{id}` returns 403 for bob (missing `task.delete`)
- [ ] `DELETE /api/tasks/{id}` succeeds for admin (has `task.delete`)
- [ ] `GET /api/tasks` without a token returns 401
- [ ] The getting-started guide has the quick start with curl examples
- [ ] `mvn -P license verify` passes
- [ ] `progress.txt` updated

## Implementation notes

- No `@Transactional` needed in the example — it is demonstrating the library, not production patterns
- The Thymeleaf UI exists only to make token acquisition frictionless — keep it minimal, no CSS framework required
- `who-example` is excluded from the release deploy — it is not published to Maven Central
- `WhoExampleApplication` declares `@EnableMethodSecurity`. Since 0.10.0 the autoconfiguration
  also enables method security by default and backs off when the application configures its own
  (see `2026-11-07-security-fixes-design.md`, I5)
- The access token displayed on the home page comes from the `@RegisteredOAuth2AuthorizedClient("demo-client")` `OAuth2AuthorizedClient` after the authorization code flow completes
- Use well-known predictable UUIDs in `data.sql` (e.g. `00000000-0000-0000-0000-000000000001`) — makes the bootstrap data easy to read and reason about
