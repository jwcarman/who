# Example: a website and an API

The team task board as one app that is both a website for people and an API for machines. People
sign in to the website with an email link or Keycloak and get a session. They, and machines acting
for them, call `/api/**` with Keycloak access tokens or who API keys, never with the session.

The other examples run on who's default chain. This one writes its own, two of them, because its
halves need opposite things: the website needs a session and CSRF protection; the API needs
neither, and must not accept a browser's session cookie. Without that, any site the person visits
could make their browser call the API as them. The two chains are the app's only security code
([`SecurityConfig`](src/main/java/org/jwcarman/who/examples/webandapi/SecurityConfig.java)):

```java
@Bean @Order(1)
SecurityFilterChain api(HttpSecurity http) {
  http.securityMatcher("/api/**")
      .with(WhoSecurity.stateless(), Customizer.withDefaults())
      .authorizeHttpRequests(a -> a.anyRequest().authenticated());
  return http.build();
}

@Bean @Order(2)
SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
  http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults())
      .authorizeHttpRequests(a -> a
          .requestMatchers(publicPaths.patterns()).permitAll()
          .anyRequest().authenticated());
  return http.build();
}
```

- `WhoSecurity.stateless()` mounts who's token and key sign-in, and nothing for browsers. The API
  chain never reads or makes a session, so it has no CSRF to protect: everything it accepts
  travels in a header a browser never adds by itself. A call without a token or key is `401` with
  `WWW-Authenticate: Bearer`.
- `WhoWebSecurity.passwordless()` mounts the website's sign-in (email links and codes, Keycloak)
  with sessions and CSRF protection. `WhoPublicPaths` opens the sign-in pages to signed-out
  visitors.
- The API chain comes first: the web chain matches every request, so a chain after it would never
  see one.

## Run it

You need Java 25 and Docker. From the repository root:

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-examples/web-and-api
```

The app is at <http://localhost:8080>. Spring Boot's Docker Compose support starts Postgres,
Mailpit (<http://localhost:8025>, where the sign-in emails land) and Keycloak
(<http://localhost:8280>, admin console `admin` / `admin`). The examples use the same ports: run
one at a time.

Keycloak's realm `who-tasks` has three people, all with the password `password`:

| Person | In who | Can |
|---|---|---|
| alice (`alice@example.com`) | an admin | read, add and delete tasks |
| bob (`bob@example.com`) | a member | read and add tasks |
| carol | not linked | nothing: who does not know her |

The realm has two clients: `who-tasks-web`, for the website's sign-in, and `who-tasks-cli`, for a
command-line tool's device flow, whose tokens are issued for the API (`who-tasks-api`).

## The website

Open <http://localhost:8080> and sign in as alice: by email (open the link in Mailpit, or type its
code) or with Keycloak. You see the board.

Both ways land on the same who identity. `Setup` links alice's identity to her email address and
to her Keycloak account, and who signs a person in only through a link the app made: never by
matching an email address.

The Keys page also lists every browser alice is signed in on, under "Where you're signed in".
Sign in in a private window too, then sign that one out from here. Signing out another browser
needs a sign-in in the last 10 minutes; past that, the page sends you to sign in again and back.

## API keys

Open **API keys**. Make a key: name it, give it a lifetime, and tick what it may do. Only
permissions you hold are offered. The page shows the key once: who keeps only its hash. Call the
API with it:

```bash
KEY=<the key>
curl -s http://localhost:8080/api/tasks -H "X-API-Key: $KEY"
curl -s http://localhost:8080/api/me -H "X-API-Key: $KEY"
```

```json
{"identityId":"…","permissions":["task.read"],"signedInWith":"api-key"}
```

The key holds only what you ticked: with `task.read`, adding a task is `403`. A key reads only
itself, so a machine can see when it expires and rotate in time:

```bash
curl -s http://localhost:8080/api/keys/self -H "X-API-Key: $KEY"
```

```json
{"id":"…","name":"laptop","scopes":["task.read"],"createdAt":"…","expiresAt":"…","lastUsedAt":"…","expired":false}
```

Keys are made, listed and revoked only on the website, by a person signed in there. The API has
no way to manage them, so a leaked key cannot make itself a broader or longer-lived one, see its
owner's other keys, or revoke them. Another person's key id is `404`. Revoke the key on the page,
and its next call is `401`.

## Access tokens

A command-line tool gets a token with the device flow. Ask Keycloak for a code:

```bash
curl -s -X POST http://localhost:8280/realms/who-tasks/protocol/openid-connect/auth/device \
  -d client_id=who-tasks-cli
```

Open its `verification_uri_complete` in a browser, sign in as `alice`, and answer **Yes**. Then
collect the token with the answer's `device_code` (until you have signed in, the answer is
`authorization_pending`; poll no faster than its `interval`, 5 seconds, and within its 10
minutes):

```bash
curl -s -X POST http://localhost:8280/realms/who-tasks/protocol/openid-connect/token \
  -d client_id=who-tasks-cli \
  -d grant_type=urn:ietf:params:oauth:grant-type:device_code \
  -d device_code=<device_code>
TOKEN=<access_token>
curl -s http://localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

`identityId` is the one alice has on the website. The token and her Keycloak sign-in there are
the same `(issuer, subject)` credential to who. carol's token is valid, from the same realm and
for the same API, and still `401`: the app never linked her.

## What each chain refuses

| Request | Answer |
|---|---|
| The website's session cookie on `/api/**` | `401`, `WWW-Authenticate: Bearer` |
| No token or key on `/api/**` | `401`, `WWW-Authenticate: Bearer` |
| A token or key on a web page | sent to `/signin`: the web chain mounts neither |
| A form post on the website without its CSRF token | `403` |
| A key on `/api/keys/self` | the key; with a token, `404` |

Errors on the API are `ProblemDetail` JSON
([`Errors`](src/main/java/org/jwcarman/who/examples/webandapi/Errors.java)); a signed-out browser
asking for a page is sent to sign in.

## In production

- Name your own authorization server and API in `issuer-uri` and `audiences`. Without the
  audience, a token your authorization server issued for any other app would sign in here; who
  refuses to start with JWTs and no audience.
- Use the same issuer for the website's provider (`who.oidc.providers.<id>.issuer`) and the API's
  tokens if a person should be one identity on both.
- Link accounts the way your app decides who may use it: invitations, a sign-up policy, an admin
  screen. `Setup` links fixed accounts only because this is an example.
- Show a new key once, keep lifetimes short, and rotate keys (`ApiKeyService.rotate`) before they
  expire.
- Real mail delivery, TLS, and your own `who.email-link.code-secret`.
