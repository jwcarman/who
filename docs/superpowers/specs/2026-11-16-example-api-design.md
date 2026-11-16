# Example: a stateless API on who

## Intent

The third of who's examples (after [the web app](2026-11-15-example-web-app-design.md) and
[the single-page app](2026-11-15-example-spa-design.md)): an API with no browser in it. People call
it with access tokens from an authorization server, machines with who API keys. It replaces the
old `who-example`. That example has its own `SecurityConfig`, an H2 database and an authorization
server faked inside the app. It also accounts for most of the roadmap's missing Javadoc, and it
no longer shows how who is meant to be used.

It teaches what the browser examples cannot: who signs a token in but never creates an identity
from one, so an app decides which accounts exist and links them; a token must be issued *for this
API* (its audience); and an API key acts as its owner, with the owner's permissions.

## Goals

- `./mvnw spring-boot:run -pl who-examples/api` on a fresh clone, with Java and Docker, gives the
  API at `http://localhost:8080`, with Postgres and Keycloak started by Docker Compose.
- The same "Who Tasks" domain as the other examples, as JSON: tasks, roles with
  `@RequiresPermission`, and `GET /api/me`.
- People: a Keycloak access token as `Authorization: Bearer`. Machines: a who API key in
  `X-API-Key`. Both reach the same identity and the same permissions.
- A person creates, lists and revokes their own API keys through the API.
- The README walks through it with `curl` alone.
- `who-example` is deleted, and the README, docs and workflows point at the new example.

## Non-goals

- Browser sign-in of any kind, sessions, CSRF, email, passkeys.
- Sign-up from a token, or inviting people: accounts exist only through `Setup`.
- An admin API for roles or members. Roles are assigned in `Setup`.
- Machine callers with Keycloak's client-credentials tokens. API keys are the machine path here.
- Front-end code, or tests beyond the backend's.

## Anti-goals

- No `SecurityFilterChain` of the app's own: who's default chain does all of it.
- No `csrf.disable()` and no session: the chain is stateless because only stateless methods are
  on.
- No token accepted without an audience check, and no deprecated grant taught as the way to get a
  token.
- No raw API key stored or logged; the key is shown once, when it is created.

## Approaches

- **Keycloak tokens, linked accounts, API keys from the API (chosen, my call):** what a real
  service does, and it uses only what who has today.
- **An authorization server inside the app (Spring Authorization Server), as `who-example` did:**
  one process, but it teaches an app to be its own token issuer, which almost none are, and it
  hides the audience lesson.
- **Sign up from the first valid token:** convenient, but who deliberately does not do it (the JWT
  guide: "Who does not create identities from tokens"), and an example should not work around who.

## The design

### Layout

```
who-examples/api/
  pom.xml               artifactId who-example-api: the starter, who-jwt, who-apikey,
                        oauth2-resource-server, Postgres, docker compose
  compose.yaml          Postgres and Keycloak (no Mailpit)
  keycloak/realm.json   realm who-tasks: users alice, bob, carol with fixed ids
  README.md
  src/main/java/...     org.jwcarman.who.examples.api
  src/main/resources/   application.yml, schema.sql
  src/test/java/...
```

### Accounts

Keycloak's realm export fixes each user's `id`, so the subjects are known in advance. On startup,
`Setup` creates the roles and, for each configured subject, an identity linked with
`FederatedCredentialService.link(identity, issuer, subject)`: alice as admin, bob as member. carol
has a Keycloak account and no who identity: her valid token gets `401`, which shows that who signs
in only accounts the app linked. The subjects and roles are `example.*` properties.

### Tokens

- The realm has one public client, `who-tasks-cli`, with the OAuth 2.0 device authorization grant
  on and the direct password grant off. `curl` starts the device flow, the person signs in at
  Keycloak in a browser, and `curl` collects the token. That is how a command-line tool gets a
  token today, and it does not teach the password grant.
- An audience mapper puts `who-tasks-api` in the token's `aud`.
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` names the realm, and
  `audiences: who-tasks-api`. A token for any other audience is refused.

### API

| Call | Who may | What |
|---|---|---|
| `GET /api/me` | anyone signed in | identity, permissions, how the call was signed in (token or key) |
| `GET/POST/DELETE /api/tasks` | by permission | the board, as in the other examples |
| `POST /api/keys` | a token only | creates a key for the caller; answers the raw key once |
| `GET /api/keys` | anyone signed in | the caller's keys: id, name, created |
| `DELETE /api/keys/{id}` | a token only | revokes one of the caller's keys |

Creating and revoking keys needs a person's token: a key cannot mint keys or revoke its siblings,
so a leaked key cannot make itself permanent. Errors are `ProblemDetail` JSON: `401` (who's chain,
with `WWW-Authenticate`), `403`, `404` for another identity's key.

### Tests

- The backend starts with a Testcontainers Postgres and Docker Compose off.
- A JWT from a test signer (who-test, or a `JwtDecoder` the test supplies) for a linked subject
  reaches `/api/tasks`; an unlinked subject and a wrong audience get `401`.
- A created key calls `/api/tasks` with its owner's permissions; a key cannot create a key; a
  revoked key gets `401`.

### Removing who-example

Delete the module and its entries in the root `pom.xml`, the workflows' excludes and api-docs
loop, the README, `docs/index.md`, getting started and any guide that names it. Add a CHANGELOG
line, and drop the roadmap's note that most Javadoc gaps are in `who-example`.

## Left to the builder

Exact package layout, how `/api/me` tells a token from a key, the test JWT signer, Keycloak's
version (the other examples' image), and the README's `curl` steps for the device flow.
