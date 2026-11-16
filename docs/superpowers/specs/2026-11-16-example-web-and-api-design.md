# Example: a website and an API, each with its own security chain

## Intent

who ships one example per kind of app built on it. Three run on who's default chain: a
server-rendered web app, a single-page app and a stateless API. This, the fourth, is an app that
writes its own `SecurityFilterChain`s, and it gives the most common real reason to: one app that is
both a website for people and an API for machines. The website needs sessions and CSRF; the API
needs neither, and must not honour a browser's session cookie.

Writing it found a gap. `WhoSecurity.all()` decides what to mount from the whole application, not
from the chain it is in, so in this app it would put browser sign-in, with sessions, into the API
chain. An API chain has to be assembled by hand today: `WhoApiKeySecurity.apiKey()`, Spring's
resource server with who's JWT converter (there is no who call for JWT alone), and a hand-written
401/403 entry point, which is the code people copy and get subtly wrong. The owner's rule: when an
example finds a gap, fix the library.

So this work is two things: **`WhoSecurity.stateless()`** in the library, and the example that
uses it.

## Goals

- An API chain is one line:
  `http.securityMatcher("/api/**").with(WhoSecurity.stateless(), withDefaults())`.
- A web chain is one line: `http.with(WhoWebSecurity.passwordless(), withDefaults())`, with
  `WhoPublicPaths` for its signed-out pages.
- `./mvnw spring-boot:run -pl who-examples/web-and-api` on a fresh clone, with Docker running,
  gives the team task board as web pages and as `/api/**`, with nothing to set up.
- The same person is the same identity on both: alice signs in to the website with Keycloak, and
  the access token her command-line tool gets from the same Keycloak signs her in to the API.
- People make, list and revoke their API keys on a web page, signed in with a session; machines
  use them on `/api/**`. This is how GitHub and Stripe split it, and it follows the API Keys guide:
  key lists are served only to people.

## Non-goals

- Every sign-in method and the whole account lifecycle: that is the web-app example's job.
- Thorough testing of the example: the library tests carry `stateless()`'s behaviour.
- A per-method JWT call (`WhoJwtSecurity.jwt()`): not until someone needs it. A chain that wants
  only keys has `WhoApiKeySecurity.apiKey()`, or turns JWT off with `who.jwt.enabled=false`.
- Changing what `all()`, `passwordless()` or who's default chain do.

## Anti-goals

- The example must not teach anything unsafe: no `csrf.disable()` in app code, no open sign-up, no
  linking by email, no suppressed warnings.
- The API chain must never sign a request in from a session cookie. Otherwise a page on another
  site could make the browser call `/api/**` as the person, and CSRF is off there.
- No step beyond `spring-boot:run` (no `docker compose up` first, no realm to import by hand).

## Constraints

- Java 25, Spring Boot 4.1, Spring Security 7; Postgres through Docker Compose, Keycloak 26.4.5 on
  port 8280, the app on 8080 (the other examples use the same ports: run one at a time).
- Breaking changes are allowed (the owner and one friend use who), but this design needs none.
- The project's code rules: no warning suppression, no star imports, no fully qualified type names
  in code, snake_case JUnit tests with `ReplaceUnderscores`, license headers.

## Approaches

1. **Two chains, each mounting what it needs, with `WhoSecurity.stateless()` added (chosen).**
   About five lines per chain, and the gap is closed for every app, not just this example.
2. **Two chains from today's parts:** `apiKey()`, Spring's resource server with who's converter,
   and a hand-written entry point; about 20 lines in the API chain. No library change, but it
   teaches the boilerplate people get wrong (the challenge header, CSRF left on).
3. **One chain with `all()` and path rules.** That is who's default chain written out, so it shows
   no reason to write your own.

The owner picked 1, and confirmed the four behaviours below.

## Part 1: `WhoSecurity.stateless()`

`public static WhoStatelessConfigurer stateless()` on `WhoSecurity`, in `who-spring-security`,
beside `all()`. It mounts every `StatelessLoginMethod` bean (today JWT and API keys), in their
order, and:

1. **Never reads or creates a session.** It sets `SessionCreationPolicy.STATELESS`, so the chain
   keeps the security context in the request only: a session cookie from another chain signs
   nothing in. Because no credential on this chain rides along automatically, a forged request
   carries nothing, and the configurer turns CSRF off for the chain itself. That is the one place
   who turns CSRF off, and the Javadoc says why.
2. **Answers like the API example.** A signed-out request gets `401`, an authenticated one without
   permission `403`, both handed to the application's `@RestControllerAdvice` through who's
   `WhoExceptionHandoff`, as `all()` does when no browser method is present, with a bare status
   when nothing handles them. A signed-out POST gets `401`, never a CSRF `403`.
3. **Fails startup with no stateless method.** With neither `who-jwt` nor `who-apikey` on (absent,
   or `enabled=false`), building the chain throws `IllegalStateException` naming both modules.
   (`all()` only logs a warning for the same thing; this design does not change that.)
4. **Challenges with each method's scheme.** RFC 6750 says a `401` carries `WWW-Authenticate`.
   `StatelessLoginMethod` gains `default Optional<String> challenge()` (empty); JWT returns
   `Bearer`. The signed-out `401` carries one `WWW-Authenticate` per method that has one, set
   before the handoff. API keys have no standard scheme, so a keys-only chain sends none. *My
   call:* this is why the API example needed its own `ApiErrors` header; that workaround goes once
   this ships. A rejected token keeps Spring's own `Bearer error="invalid_token"` answer.

It mounts no browser method, even when one is on the classpath: that is what makes two chains
possible. The builder may refactor `all()` to share code with it, with `all()`'s behaviour
unchanged.

### Library tests

In `who-spring-security` (the chain-level tests beside `WhoSecurityAllTest`):

- A chain with `stateless()` and an API key signs in with the key; a request that brings only a
  session cookie of a signed-in person is `401`.
- A signed-out POST is `401` (not `403`), with `WWW-Authenticate: Bearer` when JWT is on and none
  with keys alone; a request without permission is `403`.
- Both answers reach a `@RestControllerAdvice` that handles them.
- The chain creates no session (no `Set-Cookie`).
- With no stateless method, the context fails to start with the message naming both modules.
- Two chains in one app (`/api/**` with `stateless()`, the rest with `passwordless()`): a browser
  sign-in works on the web chain and gives no access to `/api/**`.

### Docs

- `docs/guides/getting-started.md` (writing your own chain) and the `WhoSecurity` Javadoc: the
  two-chain pattern with `stateless()`.
- `docs/guides/jwt.md` and `docs/guides/api-keys.md`: mounting them in an API chain.
- CHANGELOG (Added), and the API example drops its `WWW-Authenticate` workaround.

## Part 2: the example, `who-examples/web-and-api`

*My call on the name:* it says what the app is; the README says why it has two chains.

### What it has

- Modules: `who-spring-boot-starter`, `who-email-link`, `who-oidc` (website), `who-jwt`,
  `who-apikey` (API), with Postgres, Thymeleaf, mail (Mailpit, as in the web-app example) and
  Docker Compose.
- One Keycloak realm with two clients: a confidential one for the website's sign-in, and the
  public device-flow client of the API example, whose tokens carry the API's audience. *My call:*
  one realm, so a person is one Keycloak account and, through who's shared `(issuer, subject)`
  credential, one who identity on both chains.
- People: alice (admin) and bob (member), invited and linked by `Setup` as in the other examples;
  carol has a Keycloak account and no who identity, so she gets nowhere on either chain.

### The two chains (the point of the example)

```java
@Bean @Order(1)
SecurityFilterChain api(HttpSecurity http) {
  http.securityMatcher("/api/**")
      .with(WhoSecurity.stateless(), withDefaults())
      .authorizeHttpRequests(a -> a.anyRequest().authenticated());
  return http.build();
}

@Bean @Order(2)
SecurityFilterChain web(HttpSecurity http, WhoPublicPaths publicPaths) {
  http.with(WhoWebSecurity.passwordless(), withDefaults())
      .authorizeHttpRequests(a -> a
          .requestMatchers(publicPaths.patterns()).permitAll()
          .anyRequest().authenticated());
  return http.build();
}
```

These are the only security code in the app, apart from `@RequiresPermission`. Their comments say
what each chain is for and why the API chain must come first.

### Pages and endpoints

- Website: sign in (email link or code, Keycloak), the board (read, add, delete by permission),
  and a **Keys** page: make a key (name, lifetime, scopes from the person's own permissions),
  shown once; list keys with last use; revoke. Plain pages in the web-app example's style.
- API: `GET/POST/DELETE /api/tasks`, `GET /api/me` (as in the API example, saying whether a token
  or a key signed in), and `GET /api/keys/self` (the key reads only itself). No key management on
  the API: that belongs to people, on the website.
- Errors on `/api/**` are `ProblemDetail` JSON; the web chain keeps the web-app example's pages.

### Example tests

- The app starts with Testcontainers Postgres and Docker Compose off.
- A signed-in session reaches the board page and is `401` on `/api/tasks`.
- A key made on the Keys page reads `/api/tasks`, and is `403` adding a task it has no scope for.
- A token for a linked subject reaches `/api/me`; an unlinked one is `401`.

### README

Run it; sign in to the website as alice; make a key on the Keys page and call the API with it
using `curl`; get a token with the device flow and see it is the same identity; then why there are
two chains, what `stateless()` does (no session, no CSRF, `401`s), and why the API chain must not
honour the session cookie. In production: as the other examples' lists.

### Elsewhere

- `who-examples/pom.xml` module, `maven-publish.yml` exclude, `docs/index.md` Examples list,
  README's examples paragraph, CHANGELOG.

## Left to the builder

- Sharing code between `all()` and `stateless()`, and the exact Javadoc wording.
- Page layout and copy, reusing the web-app example's templates and stylesheet where they fit.
- How tests make a session and a token (the other examples' test helpers).
