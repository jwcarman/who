# Native test bed

## Intent

who claims to work in a GraalVM native image. Today the only proof is
`noir-app-template`: a separate repository, a full app with a frontend, Playwright, Mailpit and
every sign-in method switched on at once, a 205 MB binary and one end-to-end test. It tests
published versions, so a native break is found after a release, and when it fails it is hard to
tell which module broke.

This adds a small native test bed inside the who repository: one purpose-built app per sign-in
method, each compiled to a native binary and driven over its HTTP API by a JVM test. A native break
in any module shows up in who's own CI, against the current code, and names the module.

## Goals

- One minimal Spring Boot app per sign-in method: email link and code, passkey, API key, JWT.
  Each app depends only on the who modules that method needs.
- Each app is compiled native (Spring AOT plus GraalVM) and exercised end to end through its HTTP
  API against a real PostgreSQL: sign in, read `/who/me`, call a permission-protected endpoint,
  check the 401 for a bad credential, sign out where the method has sessions.
- Each test also checks who's security events in the native app (a sign-in publishes
  `SignInSucceeded` with the right method; a refused one publishes `SignInFailed` with the right
  reason).
- The same tests can run against the app on the JVM (fast, for writing the tests) and against
  the native binary (the real check).
- A CI workflow runs the native tests.

## Non-goals

- No browser, no Playwright, no frontend, no Mailpit.
- The apps are not examples or documentation and are never published to Maven Central.
- Replacing or changing `noir-app-template`. It keeps working as it does; whether it keeps its
  native check is a separate decision.
- Performance, binary size or startup-time measurement.
- Tracing-agent metadata capture (the template's `native-trace` profile).

## Anti-goals

- A test bed that is itself hard to maintain: no shared framework beyond a few helpers; each app
  readable on its own.
- Hiding native problems with app-side hints. If an app needs reflection or resource hints to
  work, those hints belong in who's modules (`aot.factories` / `RuntimeHints`), because every
  user's app needs them too. The apps carry no `RuntimeHints` of their own.

## Constraints

- Java 25, Spring Boot 4.1.2, the repo's existing conventions (license headers, spotless, no star
  imports, no fully qualified names, no warning suppression; new test classes snake_case with
  `@DisplayNameGeneration(ReplaceUnderscores)` and `@Nested`).
- Builds against the reactor's current SNAPSHOT of who, never a published version.
- The default build (`./mvnw verify`, `-Pci`) does not change and does not need GraalVM.
- GraalVM for JDK 25 is installed locally (`/Library/Java/JavaVirtualMachines/graalvm-25.jdk`);
  CI uses `actions/setup-java` with the `graalvm` distribution.

## Approaches considered

1. **A JVM test drives the native binary over HTTP (chosen).** Failsafe starts PostgreSQL in
   Testcontainers, launches the binary as a process on a free port, and drives it with Java's
   `HttpClient`. Real binary, real port, real database: what users ship.
2. **Spring's native test mode** (JUnit tests compiled into the image). Less code, but the tests
   run inside the binary against an in-process server, and Testcontainers inside a native image
   is painful.
3. **Shell scripts with curl.** Smallest, but passkey ceremonies and reading captured emails get
   ugly, and failures are hard to read.

The user chose 1: "we can test just using an API. We don't need a browser."

## Design

### Layout

A `who-native-tests/` directory with its own aggregator pom, added to the root pom's modules only
under a new `native-tests` profile, so `./mvnw -Pnative-tests verify` builds who and the apps from
the same reactor. Proposed modules (my call):

- `native-test-support` — test-scope helpers shared by the four test modules: start PostgreSQL
  (Testcontainers), start the app (the native binary when `-Dapp.binary=<path>` is set, otherwise
  in-process on the JVM), an HTTP client that keeps cookies and handles CSRF, and a client for the
  app's test endpoints (captured mail, recorded events).
- `native-email-link`, `native-passkey`, `native-api-key`, `native-jwt` — one app each, with its
  test (`*IT`, failsafe).

Every module skips deploy, javadoc and sources jars, and is excluded from release and Sonar.

### Each app

- A `@SpringBootApplication`, `who-spring-boot-starter` plus only the method's who module, the
  PostgreSQL driver, and the web/security starters it needs.
- One security chain using who's own configurers the way the guides tell users to.
- Two endpoints of its own: `GET /api/hello` (any signed-in identity) and `GET /api/admin`
  (requires a permission through who's RBAC and method security), so permissions are exercised
  natively in every app.
- Test-only endpoints that exist because these apps exist only for tests:
  - `GET /test/events` — the who events a recording `@EventListener` has seen (type and the
    relevant fields), and `DELETE /test/events` to clear;
  - email-link app only: `GET /test/mail` — sign-in emails captured by an in-memory
    `JavaMailSender` stand-in, so the test reads the link or code without Mailpit.
- Setup the test needs (create an identity, link an email, issue an API key, link a JWT
  credential, grant a role) is done by a small `/test/setup/...` endpoint or a startup runner in
  that app, calling who's own services — my call per app, whichever is shorter.

### Each test

Per method, at minimum (my call on exact cases):

- **Email link and code:** request a link → read it from `/test/mail` → follow it → `/who/me` is
  the identity → `/api/admin` works with the role and is 403 without it → sign out → `/who/me` is
  anonymous. The same with a typed code. A wrong code is the uniform 401. Events:
  `SignInEmailRequested`, `SignInSucceeded(EMAIL_LINK / EMAIL_CODE)`, `SignInFailed(WRONG_CODE)`,
  `SignedOut`.
- **Passkey:** sign in by email first (the passkey app also needs email link, as users' apps do),
  register a passkey with a software authenticator (based on `who-autoconfigure`'s test
  `PasskeyDevice`, using webauthn4j-test), sign out, sign in with the passkey, `/who/me`, a forged
  assertion is 401. Events: `CredentialAdded(PASSKEY)`, `SignInSucceeded(PASSKEY)`,
  `SignInFailed(INVALID_ASSERTION)`.
- **API key:** a valid key reaches `/api/hello`; a bad key is 401 even on a permitted endpoint; no
  header reaches it anonymously. Events: `SignInSucceeded(API_KEY)`, `SignInFailed(UNKNOWN_KEY)`.
- **JWT:** the app is a resource server whose public key is a file the test generated
  (`public-key-location`), so no JWK endpoint is needed; the test signs tokens with the private
  key. A known subject reaches `/api/hello`; an unknown one is 401. Events:
  `SignInSucceeded(JWT)`, `SignInFailed(UNKNOWN_CREDENTIAL)`.

### Build

The `native-tests` profile in each app module runs Spring Boot's `process-aot` and GraalVM's
`native-maven-plugin` (`add-reachability-metadata`, `compile-no-fork` at `package`), then
failsafe with `app.binary` pointing at the binary — the same shape as the template's
`native-e2e` profile. Without `-Dnative`, an app's `*IT` runs on the JVM (my call: a second
property, e.g. `-Dnative=false`, or a separate `jvm` execution — whichever is simpler in Maven).

### CI

A `.github/workflows/native.yml`: a matrix with one job per app, `setup-java` with GraalVM 25,
`./mvnw -Pnative-tests -pl who-native-tests/<app> -am verify`. Runs on push to `main`, weekly,
and on `workflow_dispatch` (my call — a native build per app takes minutes, so not on every pull
request). CONTRIBUTING's release checklist gains "the native workflow is green on the release
commit". On failure, upload the app log.

### Fixing who as we go (user's decision)

Apart from passkey sessions, who's modules have never been compiled native on their own, so the
first builds are expected to fail. Fixing those failures is in scope: when an app fails to build
or run native because a who module lacks hints (`aot.factories` / `RuntimeHints`) or reachability
metadata, the fix goes into that module, with a unit test of the hints in the style of
`PasskeyRuntimeHintsTest`, and the work continues. The email-link app is built first, as the
pathfinder for the shared core, before the other three. Each fix is recorded in the CHANGELOG.

### Settled after the builder check (my calls)

- **Schema.** Three apps create who's schema the way `docs/guides/native.md` recommends, through
  Spring Boot's Liquibase integration (`spring-boot-starter-liquibase`, with
  `add-reachability-metadata` supplying Liquibase's metadata). The JWT app instead uses who's
  `who.initialize-schema` scripts, so both documented paths are proven native.
- **Port, origin and relying party.** The test picks a free port before it launches the app (as
  the template does) and passes it, the passkey origin `http://localhost:<port>` and relying-party
  id `localhost`, the database URL and credentials, and the base URL for links as command-line
  arguments, identical for the JVM and the native run.
- **JVM mode** starts the app in-process (`SpringApplication` in the test JVM), as the template's
  `E2eIT` does; native mode starts the binary as a process and writes its output to
  `target/app.log`. One launcher interface in `native-test-support` hides the difference. Each app
  module holds its own `*IT`, so no module depends on another's repackaged jar.
- **Test isolation.** Each test creates its own identities with unique addresses and names; nothing
  is truncated between tests. One PostgreSQL container per test class.
- **CSRF and sign-out.** The HTTP helper follows who's documented API: it reads the CSRF token the
  way who hands it to clients and sends it on state-changing requests, including sign-out.
- **Release and Sonar.** Neither the release workflow (`-P release`) nor the CI build (`-Pci`)
  activates `native-tests`, so the modules never reach them; the poms still set deploy and
  install skips as a guard.
- **`PasskeyDevice`** is copied into `native-test-support` (it is package-private test code in
  `who-autoconfigure`); `who-test` stays untouched.

## Left to the builder

- Exact Maven wiring of JVM vs native runs, module names and package names.
- Exact test cases beyond the minimum above, and test names.
- How each app seeds its identities and roles.
