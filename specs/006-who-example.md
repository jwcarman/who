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
- `spring-authorization-server`
- `spring-boot-starter-thymeleaf`
- `spring-boot-starter-jdbc`
- H2 (runtime scope)

Add to root `pom.xml` `<modules>` but mark it in the parent POM as not for release
(it should not be published to Maven Central).

### Embedded Authorization Server

Spring Authorization Server runs on the same port (8080) as the API. Configure:

**Registered OAuth2 client:**
```
client-id:     demo-client
client-secret: secret
grant-types:   authorization_code, refresh_token
redirect-uri:  http://localhost:8080/login/oauth2/code/demo-client
scopes:        openid, profile, email
```

**In-memory users:**
| Username | Password | Description         |
|----------|----------|---------------------|
| alice    | password | viewer only         |
| bob      | password | can read and write  |
| admin    | password | full access         |

**Issuer:** `http://localhost:8080`

Configure the authorization server to include the username as the `sub` claim in issued
JWTs. This makes the bootstrap data predictable.

### Security configuration

Two security filter chains:

1. **Authorization server chain** (`@Order(1)`) — handles `/oauth2/**` and
   `/.well-known/**` endpoints. Provided by Spring Authorization Server.

2. **API resource server chain** (`@Order(2)`) — handles `/api/**`. Stateless, JWT
   authentication using `WhoJwtAuthenticationConverter`. Requires authentication for
   all `/api/**` requests. CSRF disabled.

3. **Default chain** (`@Order(3)`) — handles everything else (the Thymeleaf UI).
   Uses form login. Redirects to `/` after login.

### Database bootstrap

Use H2 in-memory database (PostgreSQL compatibility mode). Initialize schema from all
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
Task save(Task task);
void deleteById(UUID id);
```

### API controllers

**`TaskController`** — base path `/api/tasks`:
```
GET    /api/tasks          requires task.read   → list all tasks
GET    /api/tasks/{id}     requires task.read   → get one task
POST   /api/tasks          requires task.write  → create task
DELETE /api/tasks/{id}     requires task.delete → delete task
```

Use `@PreAuthorize("hasAuthority('task.read')")` etc. on each method.

**`MeController`** — `GET /api/me` (no special permission required, just authenticated):
Returns the current user's `WhoPrincipal` — their `identityId` and `permissions`.
Useful for verifying the library is working correctly.

### Thymeleaf UI

Minimal — just enough to get a token into a developer's hands:

**`/`** — home page. If not logged in, shows a "Login" button. If logged in, shows:
- The user's username
- Their identity ID (from `WhoPrincipal` resolved via `WhoService`)
- Their permissions
- Their access token (so they can copy it for curl commands)
- Curl examples pre-filled with the token

### README update

Update the root `README.md` to include a "Quick Start" section:

```bash
# Clone and run
git clone https://github.com/jwcarman/who
cd who
mvn install -DskipTests
mvn spring-boot:run -pl who-example

# 1. Open http://localhost:8080 in a browser
# 2. Log in as alice / password
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
- [ ] `GET /api/me` with alice's token returns her identity ID and `[task.read]`
- [ ] `GET /api/me` with admin's token returns admin's identity ID and `[task.read, task.write, task.delete]`
- [ ] `GET /api/tasks` succeeds for alice (has `task.read`)
- [ ] `POST /api/tasks` returns 403 for alice (missing `task.write`)
- [ ] `POST /api/tasks` succeeds for bob (has `task.write`)
- [ ] `DELETE /api/tasks/{id}` returns 403 for bob (missing `task.delete`)
- [ ] `DELETE /api/tasks/{id}` succeeds for admin (has `task.delete`)
- [ ] `GET /api/tasks` without a token returns 401
- [ ] Root `README.md` updated with Quick Start section including curl examples
- [ ] `mvn -P license verify` passes
- [ ] `progress.txt` updated

## Implementation notes

- No `@Transactional` needed in the example — it is demonstrating the library, not production patterns
- The Thymeleaf UI exists only to make token acquisition frictionless — keep it minimal, no CSS framework required
- `who-example` should be excluded from the Maven `release` profile — it is not published to Maven Central
- `@EnableMethodSecurity` goes here in the example's security config, not in `who-autoconfigure`
- The access token displayed on the home page can be retrieved via Spring Security's `OAuth2AuthorizedClientService` after the authorization code flow completes
- Use well-known predictable UUIDs in `data.sql` (e.g. `00000000-0000-0000-0000-000000000001`) — makes the bootstrap data easy to read and reason about
