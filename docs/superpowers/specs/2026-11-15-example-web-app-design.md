# Example: a server-rendered web app on who

## Intent

who should ship its own examples, one per kind of app people build on it, so a newcomer can
find the one shaped like their app, run it, and copy from it. Four are planned:

1. a server-rendered web app (this design),
2. a single-page app with a Spring backend (sessions and CSRF),
3. a pure API (tokens from an outside identity provider, API keys for machines),
4. an app that writes its own security chains (`WhoSecurity.all()`, `WhoPublicPaths`).

This one shows who at its most seamless: add the jars, set the required properties, write a
sign-up policy, and every sign-in method works on who's default chain with no
`SecurityFilterChain` in the app.

## Goals

- `./mvnw spring-boot:run -pl who-examples/web-app` on a fresh clone, with Docker running, gives
  a working app at `http://localhost:8080` in which every sign-in method can be tried, with no
  accounts, credentials or setup steps beyond Docker.
- Sign in with an email link or a typed code, a passkey, or a provider: the local Keycloak always;
  Google and GitHub when their credentials are in the environment.
- Show the whole who lifecycle a real app has: an invited person's first sign-in, adding a
  passkey, linking and unlinking provider accounts, a provider sign-up the policy allows, one it
  refuses, and the `no-account` / `already-linked` outcomes.
- Show `@RequiresPermission` guarding real pages and actions, with roles from `who-rbac`.
- The page JavaScript is the code the guides show, so the guides and the example teach the same
  thing.

## Non-goals

- Thorough testing. One test that the app starts, and one that a signed-in member sees the board
  and a signed-out request does not, are enough.
- Production hardening (TLS, real mail delivery, secrets management). The README says what
  changes for production in a short list.
- A polished UI. Plain, readable pages with a small stylesheet.
- Examples 2 to 4, and changes to the existing `who-example`. It stays as it is until it is
  redone as example 3 or 4.

## Anti-goals

- The example must not teach anything unsafe: no open sign-up, no linking by email, no
  `csrf.disable()`, no hand-written security chain, no suppressed warnings.
- It must not need a step outside `spring-boot:run` (no `docker compose up` first, no realm to
  import by hand, no credentials to create) for the Keycloak, email-link and passkey paths.
- It must not hide who behind a framework: no htmx, no SPA framework, no build step for the
  front end.

## Constraints

- Lives in the who repo, built in the reactor like the other modules, never published to Maven
  Central. `.github/workflows/maven-publish.yml` excludes `who-example` today; it must exclude the
  new modules too, as must the `api-docs.yml` exclusion.
- Same rules as the rest of who: license headers, spotless, no star imports, no suppressed
  warnings, snake_case test methods with `ReplaceUnderscores`.
- Java 25, Spring Boot 4.1, PostgreSQL, matching who's own floors.
- Takes who's modules through the starter and the BOM the way an app would, with no version on
  who's artifacts.

## Approaches

**Running it.** Considered: (1) Spring Boot's Docker Compose support starting Postgres, Mailpit and
Keycloak; (2) H2 in memory with the email link printed to the log and no local provider; (3) Postgres
and Mailpit, but Google/GitHub credentials required. **Chosen: (1).** It is the only one in which
every sign-in method works on first run, and the owner wants every example set up this way.

**Pages.** Considered: (1) Thymeleaf pages plus one plain JavaScript file; (2) htmx; (3) Spring
Security's generated login pages. **Chosen: (1).** who's sign-in endpoints answer JSON and the
guides drive them with `fetch`; htmx does not help with WebAuthn and adds a library to learn;
Spring's generated pages know nothing of who's pending sign-up or linking and would hide the
part people copy.

## The design, as decided

### Layout

```
who-examples/
  pom.xml                       aggregator, parent who-parent, packaging pom
  web-app/
    pom.xml                     artifactId who-example-web-app
    compose.yaml                postgres, mailpit, keycloak
    keycloak/realm.json         the "who-example" realm, imported at start
    README.md                   how to run, what to try, what changes for production
    src/main/java/...           org.jwcarman.who.examples.webapp
    src/main/resources/
      application.yml
      templates/                Thymeleaf
      static/who.js             the fetch calls from the guides
      static/app.css
    src/test/java/...
```

The root `pom.xml` lists `who-examples`; `who-examples/pom.xml` lists `web-app`, and later the
other examples.

### Dependencies

`who-spring-boot-starter`, `who-email-link`, `who-passkey`, `who-oidc`; Spring Boot's web,
Thymeleaf (with `thymeleaf-extras-springsecurity6`), mail, oauth2-client, jdbc,
`spring-boot-docker-compose` (scope as Boot recommends for development-only use), the Postgres
driver; `who-test` and `spring-boot-starter-test` for tests.

### Services (compose.yaml)

- **Postgres**, picked up by Boot's service connection.
- **Mailpit**: SMTP on `localhost:1025`, web inbox on `http://localhost:8025`. `spring.mail.*`
  points at it.
- **Keycloak** in dev mode on `http://localhost:8280`, importing `keycloak/realm.json`: realm
  `who-example`, a confidential client `who-example` whose redirect URI is
  `http://localhost:8080/login/oauth2/code/keycloak`, and two users, `alice` and `bob`, password
  `password`, with verified emails `alice@example.com` and `bob@example.com`. The app reaches it
  at the same `localhost:8280` URL the browser does, so the issuer matches.

### Sign-in configuration (application.yml)

- `who.initialize-schema: always`, as database.md recommends for local development, with a
  comment pointing at Flyway for production. The app's own table (`task`) comes from
  `spring.sql.init` with `CREATE TABLE IF NOT EXISTS`.
- Email link: `link-base-url: http://localhost:8080/signin/link`, `from: who@example.com`, a
  `code-secret` of 32+ characters marked in a comment as a development value to replace.
- Passkey: `rp-id: localhost`, `allowed-origins: http://localhost:8080`, `rp-name: Who Tasks`.
- Providers: registration `keycloak` by its endpoints with `who.oidc.providers.keycloak.issuer`
  (built: no discovery at startup, so neither the app nor its tests wait for Keycloak), always
  on; `google` and `github` in Spring profiles of those names, with client id and secret from
  `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` and `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET`
  (built: Boot refuses a registration with an empty client id, so they cannot sit in the default
  configuration). Only configured providers show a button.
- `who.oidc.providers.keycloak.trust-email: true`, with a comment: this realm is ours and marks
  addresses verified; never set it for a provider you do not control.
- `who.oidc.success-url: /`, `failure-url: /signin`; `who.security.public-paths` lists `/signin`,
  `/signin/**`, `/app.css`, `/js/**`.

### Who gets in

- **The first admin.** On startup, if no identity has the email credential
  `example.admin-email` (default `admin@example.com`), the app creates one, registers that
  address, and assigns the `admin` role. Startup is idempotent.
- **Invites.** An admin enters an email on the Members page. The app creates an identity,
  registers the address with `EmailCredentialService`, and assigns `member`. The invitee signs in
  with an email link from the Mailpit inbox.
- **Provider sign-up.** The app's `WhoSignUpPolicy` allows sign-up only from the local Keycloak
  realm, decided by issuer, and refuses every other provider, delegating to
  `ClosedSignUpPolicy`. A Google or GitHub account that is not linked therefore lands on
  `/signin?error=no-account`, which says to sign in another way and link it from the profile page.
  The policy's `SignUpDecision.allow(setup)` assigns `member` to identities it creates.
  The policy's javadoc-style comment says why: allow by an issuer or a claim the provider
  vouches for, never by email domain.

### Roles and pages

Roles are created at startup if missing: `member` with `task.read` and `task.create`; `admin`
with those plus `task.delete` and `member.manage`.

| Page | Who sees it | What it shows |
|------|-------------|---------------|
| `/signin` | anyone | email address field (sends a link, then offers the typed code), "Sign in with a passkey", one button per enabled provider, the message for any `error` parameter |
| `/signin/link` | anyone | redeems `#token=...` from the link and goes to `/` |
| `/signin/confirm` | anyone with a pending sign-up | "Create an account with Keycloak as alice@example.com?" with Create and Cancel |
| `/` | `task.read` | the task board: list, add (`task.create`), delete (`task.delete`) |
| `/profile` | signed in | the identity's credentials: email address, passkeys (add, remove), linked provider accounts (link, unlink), and sign out |
| `/members` | `member.manage` | members and their roles; invite by email |

The provider buttons on `/signin` and the link buttons on `/profile` are plain links to Spring's
`/oauth2/authorization/{id}`. Everything else that talks to who goes through `who.js`.

### who.js

One plain ES module, no build step, with the calls the guides show: reading the CSRF token from
the cookie, requesting a link and redeeming a link or code, the passkey registration and sign-in
ceremonies, reading and confirming or cancelling the pending sign-up, and sign-out. Each function
carries a comment naming the guide section it comes from. If a guide's snippet and the example
disagree, fix the guide in the same change.

### Tests

- `the_app_starts`: the context loads with Docker Compose turned off and a Testcontainers
  Postgres in its place.
- `a_member_sees_the_board_and_a_stranger_is_sent_to_sign_in`: with `WhoTestSessions`, a
  signed-in member gets the board; a signed-out request is sent to `/signin`.

Tests must run in the normal `./mvnw verify` with no Keycloak or Mailpit; anything that needs
them at startup (the Keycloak `issuer-uri` discovery, the mail sender) is replaced in the test
configuration.

### Docs

- `who-examples/web-app/README.md`: prerequisites (Java 25, Docker), the run command, a "things to
  try" walkthrough (sign in as admin through Mailpit, invite bob, add a passkey, link Keycloak as
  alice, try Google unlinked to see `no-account`), how to turn on Google and GitHub, and what
  changes for production.
- The repo README and `docs/guides/getting-started.md` point at the examples, and `docs/index.md`
  links them.

## Left to the builder

- How startup waits until Keycloak serves the realm before Boot's `issuer-uri` discovery runs
  (a compose healthcheck Boot honours, or another mechanism). Verify it on a cold start.
- The exact Keycloak and Mailpit image tags, pinned to a current release.
- Page markup and styling, within "plain and readable".
- Package layout inside `org.jwcarman.who.examples.webapp`.
- Whether the app's task store uses `JdbcClient` directly or a small repository class.

## Calls I made (not yet confirmed by the owner)

- The app is a team task board.
- Provider sign-up is allowed for the local Keycloak realm only; Google and GitHub sign in only
  once linked.
- The first admin is created from `example.admin-email` at startup.
- Module path `who-examples/web-app`, artifact `who-example-web-app`.
