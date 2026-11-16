# Example: an API

The same team task board as the [web app](../web-app) and [single-page app](../spa) examples, as
a JSON API with no browser in it. People call it with access tokens from an authorization server
(Keycloak here); machines call it with who API keys. The app has no `SecurityFilterChain` of its
own: `who-jwt` and `who-apikey` are its only sign-in methods, so who's default chain is stateless,
with no session and no CSRF.

It shows what the browser examples cannot:

- who signs a token in, but never creates an identity from one. The app decides which accounts
  exist, and links them (`Setup`).
- A token must be issued *for this API*: its audience is checked.
- An API key holds only the permissions it was made with, expires, and is made and revoked only
  with a person's token.

## Run it

You need Java 25 and Docker. From the repository root:

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-examples/api
```

The API is at <http://localhost:8080>. Spring Boot's Docker Compose support starts Postgres and
Keycloak (<http://localhost:8280>, admin console `admin` / `admin`) from `compose.yaml`. The
examples use the same ports: run one at a time.

Keycloak's realm `who-tasks` has three people, all with the password `password`:

| Keycloak user | In who | Can |
|---|---|---|
| alice | linked as an admin | read, add and delete tasks |
| bob | linked as a member | read and add tasks |
| carol | not linked | nothing: her valid token gets `401` |

## Get a token

A command-line tool gets a token with the OAuth 2.0 device authorization grant: it asks Keycloak
for a code, the person signs in at Keycloak in a browser, and the tool collects the token. The
realm's client, `who-tasks-cli`, allows only that grant: no password grant.

Ask for a code:

```bash
curl -s -X POST http://localhost:8280/realms/who-tasks/protocol/openid-connect/auth/device \
  -d client_id=who-tasks-cli
```

Open the `verification_uri_complete` from the answer in a browser, sign in as `alice` / `password`,
and answer **Yes** to let the device in. Then collect the token with the `device_code` from the
same answer:

```bash
curl -s -X POST http://localhost:8280/realms/who-tasks/protocol/openid-connect/token \
  -d client_id=who-tasks-cli \
  -d grant_type=urn:ietf:params:oauth:grant-type:device_code \
  -d device_code=<device_code>
```

Until you have signed in, the answer is `authorization_pending`; then it holds an
`access_token`. Keep it:

```bash
TOKEN=<access_token>
```

The token lasts five minutes, Keycloak's default; get another the same way.

## Call the API

```bash
curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

```json
{"identityId":"…","permissions":["task.read","task.delete","task.create"],"signedInWith":"token"}
```

```bash
curl -s -X POST http://localhost:8080/api/tasks -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"title":"Ship the API example"}'
curl -s http://localhost:8080/api/tasks -H "Authorization: Bearer $TOKEN"
curl -s -X DELETE http://localhost:8080/api/tasks/<id> -H "Authorization: Bearer $TOKEN"
```

Get a token as `bob` and his `DELETE` is `403`, a `ProblemDetail`:

```json
{"detail":"You do not have permission","instance":"/api/tasks/…","status":403,"title":"Forbidden"}
```

carol's token is valid, issued by the same realm for the same API, and still `401`: who signs in
only the accounts the app linked, and `Setup` never linked her. With no token at all, the answer
is `401` with `WWW-Authenticate: Bearer`. A token Spring rejects (expired, malformed, signed by
another key, or for another audience) is `401` with `WWW-Authenticate: Bearer
error="invalid_token"`.

## API keys

A machine that calls the API on someone's behalf gets an API key. Make one with a person's token,
naming its lifetime and the permissions it may use; each must be one the person has:

```bash
curl -s -X POST http://localhost:8080/api/keys -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"laptop","lifetimeDays":30,"scopes":["task.read"]}'
```

```json
{"id":"…","name":"laptop","key":"who_…","scopes":["task.read"],"expiresAt":"…"}
```

That is the only time the key is shown: who keeps its hash. Call with it:

```bash
KEY=<key>
curl -s http://localhost:8080/api/tasks -H "X-API-Key: $KEY"
curl -s http://localhost:8080/api/me -H "X-API-Key: $KEY"
```

`/api/me` says `"signedInWith":"api-key"` and `"permissions":["task.read"]`: the key's scopes,
even when its owner can do more. Adding a task with it is `403`.

List your keys, with no secret in them, and revoke one:

```bash
curl -s http://localhost:8080/api/keys -H "Authorization: Bearer $TOKEN"
curl -s -X DELETE http://localhost:8080/api/keys/<id> -H "Authorization: Bearer $TOKEN"
```

The next call with a revoked key is `401`.

`ApiKeyService` trusts its caller, so [`KeysController`](src/main/java/org/jwcarman/who/examples/api/KeysController.java)
decides who may manage which key:

- Only a person's token makes or revokes a key. A key gets `403` there, so a leaked key cannot
  make itself a broader or longer-lived one, or revoke its siblings.
- A key is revoked only by its owner. Another person's key id is `404`, as if it did not exist.

## In production

- `issuer-uri` and `audiences` name your authorization server and your API. Without the
  audience, a token your authorization server issued for any other app would sign in here; who
  refuses to start with JWTs and no audience.
- Link accounts the way your app decides who may use it: an invitation, a sign-up flow, an admin
  screen. `Setup` links fixed accounts only because this is an example.
- Show a new key once, and tell people to keep it in a secret store. Pick lifetimes as short as
  the machine can live with, and rotate keys (`ApiKeyService.rotate`) before they expire.
- Set `who.initialize-schema: never` and manage the schema with Flyway or Liquibase (see the
  [Database](https://jwcarman.github.io/who/guides/database/) guide).
