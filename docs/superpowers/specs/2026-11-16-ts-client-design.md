# A TypeScript client for who

## Intent

who's browser API is small and settled: about twenty endpoints under `who.base-path` (default
`/who`), listed in `docs/guides/configuration.md#endpoints`. Today every browser app hand-writes
the same plumbing to call them: the CSRF cookie and header, the WebAuthn byte encoding, the
email-link fragment, and the status codes that mean "signed out" or "sign in again". The examples
carry four copies of it: `web-app` and `web-and-api` (`static/js/who.js`, 229 lines each),
`observability` (72 lines) and `spa` (`frontend/src/who.ts`, 265 lines, typed).

The owner wants one strongly typed client, written in TypeScript, **published to npm as
`@jwcarman/who-client`** so that anyone using who can install it. who is a security library: the
fiddly, security-relevant parts of the browser side (CSRF, WebAuthn encoding, never leaving a
token in the address bar) should be written and tested once, by who. The examples then use it
instead of their copies.

The owner is new to JavaScript and TypeScript tooling and loves TypeScript; the package should
follow what an experienced TypeScript library author would do, and match the owner's roux library
(`@jwcarman/roux-web`) wherever who has no reason to differ.

## Goals

- One package, `@jwcarman/who-client`, covering every browser endpoint who serves.
- Strong types: every call says what it answers and every way it can fail, so the compiler checks
  a caller's handling.
- Created with a factory and a base URL: `createWhoClient({ baseUrl: '/who' })`.
- Built, tested and used by the examples in the same Maven build as who, so a server change and
  its client change land and are tested in one commit.
- Published to npm from the GitHub release, at the same version as who's Maven release, with
  provenance, through npm trusted publishing (no npm token in the repo).
- All four examples use it; their hand-written clients are deleted.

## Non-goals

- API keys and JWT bearer tokens. The client is for browser sessions (cookies, CSRF, passkeys,
  provider redirects). A later version may add more.
- Server-side or Node use. The client runs in a browser; it may use `document`, `location`,
  `history` and `navigator.credentials`.
- Cross-site use (the app on one site, who on another). See Constraints.
- UI components or framework bindings (no React hooks). The client is framework-agnostic; an app
  wraps it however it likes.
- Rewriting the guides around the client. The guides keep showing the HTTP calls, which are the
  contract; a new guide shows the client.

## Anti-goals

- **A client that hides a security decision.** Linking a provider account must stay an explicit
  call the app makes after asking the person (the guide's warning stands). The client never
  confirms a link or a sign-up on its own.
- **A sign-in token left in the address bar or history.** The email-link and address-confirm
  helpers take the token out of the URL before anything else.
- **A runtime dependency.** Zero runtime dependencies; dev tooling only.
- **Drift between the examples and the server.** The examples never use a published version of
  the client, only the one built beside the server they run with.

## Constraints

- who answers errors with **no body of its own**: it hands every failure to the application's
  exception handling (`WhoExceptionHandoff`), so the body's shape is the application's. This
  stays: the client must not depend on the body.
- The status alone is not enough: a `401` from an endpoint that needs a recent sign-in means
  **either** "signed out" **or** "signed in too long ago". Hence the `Who-Error` header below.
- CSRF is Spring's `csrf.spa()`: a script-readable `XSRF-TOKEN` cookie, echoed in the
  `X-XSRF-TOKEN` header on every unsafe request. The cookie exists only after a response from the
  server, changes at sign-in and sign-out, and must be read fresh on each call.
- Because the page must read who's `XSRF-TOKEN` cookie, the page and who must share cookies: the
  same origin, or same-site hosts with the cookie's domain set to cover both. A `baseUrl` may be
  absolute (for example `https://auth.example.com/who`), but the client documents that this works
  only when the cookie is readable from the page, and sends `credentials: 'include'` only for an
  absolute `baseUrl` (`'same-origin'` otherwise).
- Project rules: no warning suppression of any kind (no `eslint-disable`, no `@ts-ignore` /
  `@ts-expect-error`), Apache 2.0 headers on source files, explicit imports.
- Versions: the Maven release workflow sets the version from the release tag (bare semver, no
  `v`); npm must publish exactly that version.
- Node: the build uses the Node version the spa example already pins (`node.version` in
  `who-examples/spa/pom.xml`, `v26.13.0`); hoist it to one property both modules use.

## Approaches

The examples must track who's **unreleased** endpoints (the endpoint-root branch moved every
path), so they cannot take the client from npm.

- **A. A `who-client/` package in this repo, built by Maven (chosen).** A reactor module runs
  `npm ci`, the checks, the tests and the build through `frontend-maven-plugin` (already used by
  the spa example), before the examples. The spa depends on it by a `file:` path; the three
  no-bundler examples get the built file copied into their static resources. npm publishing joins
  the GitHub release workflow. Costs Node in one more Maven module.
- B. A separate repository published to npm, the examples installing it from npm. Rejected: the
  examples would lag a release behind the server, and two repositories must be kept in step.
- C. A package in this repo built only by npm, its output committed into the examples. Rejected:
  a committed build output drifts silently from its source.

The owner chose A.

## Error shape

Three options were weighed (owner chose 2):

1. Status only, plus the app's decoder. No server change, but `401` stays ambiguous and costs an
   extra `me()` call to resolve.
2. **who also stamps a machine-readable code on each failure it hands off, in a `Who-Error`
   response header; the application still writes the body.** Chosen: precise, typed reasons for
   the client without taking the body away from the application.
3. who owns the body (a `ProblemDetail` per error). Rejected: reverses who's decision that the
   application formats its own errors.

### Server side: the `Who-Error` header

- A small interface in who-spring-security (`org.jwcarman.who.spring.security.web`), for example
  `WhoErrorCode { String errorCode(); }`, implemented by every exception who hands off.
- `WhoExceptionHandoff.handOff` sets `Who-Error: <code>` on the response **before** handing the
  exception to the application's resolver (or falling back to `sendError`), when the exception
  carries a code and the response is not committed. One change covers every filter, since every
  who failure already goes through `handOff`.
- The codes are a public, documented contract, kebab-case, stable across releases:

  | Exception | Status | `Who-Error` |
  |---|---|---|
  | `NotSignedInException` | 401 | `not-signed-in` |
  | `ReauthenticationRequiredException` | 401 | `reauthentication-required` |
  | `SignInFailedException` | 401 | `sign-in-failed` |
  | `InsufficientUserAuthenticationException` | 401 | `insufficient-user-authentication` |
  | `RecentSignInUnavailableException` | 403 | `recent-sign-in-unavailable` |
  | `InvalidPasskeyRegistrationException` | 400 | `invalid-passkey-registration` |
  | `InvalidEmailAddressConfirmationException` | 400 | `invalid-confirmation` |
  | `EmailCredentialNotFoundException` | 404 | `email-address-not-found` |
  | `EmailAddressTakenException` | 409 | `email-address-taken` |
  | `PendingSignUpNotFoundException` | 404 | `no-pending-sign-up` |
  | `SignUpRefusedException` | 403 | `sign-up-refused` |
  | `UnknownProviderException` | 404 | `unknown-provider` |
  | `AlreadyLinkedException` | 409 | `already-linked` |

- Failures who hands off as Spring's own exceptions (an `AccessDeniedException`, a
  `ResponseStatusException` for an unknown session) and failures Spring answers itself (its
  passkey owner check's `403`) carry no header; the client falls back to the status.
- An application whose error handler resets the response drops the header; the client then falls
  back to the status, as for option 1. The errors guide says so.
- For an absolute (cross-origin) `baseUrl`, `Who-Error` must be listed in
  `Access-Control-Expose-Headers`; the client guide says so.
- The errors guide (`docs/guides/errors.md`) documents the header and the table.

## Design

### Package

- Lives at `who-client/` in the repo root; npm name `@jwcarman/who-client`; Apache-2.0;
  `"type": "module"`; `repository.directory` `who-client`; `files: ["dist"]`; one export:
  `{ ".": { "types": "./dist/index.d.ts", "import": "./dist/index.js" } }`.
- `dist/index.js` is a **single ESM file** (so a page without a bundler, or jsDelivr, can import
  it by one URL) and `dist/index.d.ts` its types.
- Tooling mirrors roux-web: TypeScript in strict mode, `tsc` for the declarations, `vite build` in
  library mode for the JavaScript, Vitest for tests, `msw` to fake the server in tests, ESLint and
  Prettier from a separate `lint-tools` package as roux does.
- The version in git is `0.0.0-dev` and never changes; only the release workflow sets a real one.
  (Nothing reads the in-git version: the examples use the package by path.)
- A `README.md` for npm: install, a short example, a link to the client guide.

### Creating a client

```ts
import { createWhoClient } from '@jwcarman/who-client';

const who = createWhoClient();                       // baseUrl '/who'
const who = createWhoClient({ baseUrl: '/auth' });   // a custom who.base-path
const who = createWhoClient({
  baseUrl: '/who',
  decodeError: async (response) => (await response.json()) as ProblemDetail,
});
```

- `baseUrl` is the one setting for where who is: relative (resolved against the page) or
  absolute. It is validated when the client is created: a trailing `/` is dropped, a query or
  fragment is refused with an error.
- `decodeError` (optional) turns a failed response's body into the application's own error type;
  its return type becomes the `detail` type of every result, by generics. The default reads a JSON
  body if there is one, else gives `undefined`.
- `fetch` (optional) replaces the global `fetch`, for tests and instrumentation.

### Results

Every call that can fail in an expected way answers a result rather than throwing:

```ts
type WhoResult<T, R extends string, D> =
  | { ok: true; value: T }
  | { ok: false; reason: R; status: number; detail: D };
```

- `reason` is the client's classification: the `Who-Error` header when present (mapped to the
  call's reason), else the endpoint and status. A string union per call, so the compiler knows
  every reason a call can give.
- `detail` is what `decodeError` made of the body.
- `unwrap(result)` returns the value or throws a `WhoError` carrying `reason`, `status` and
  `detail`, for callers who prefer exceptions. The style is chosen per call.
- **Thrown, never a result:** network failures, a status the call does not expect (including any
  `5xx`), a malformed success body, and an exception from `decodeError`. These are `WhoError`s with
  reason `'unexpected'` (or the original error, for a network failure).
- Reason names are shared across calls where they mean the same thing:
  - `'signed-out'` (`not-signed-in`) and `'reauthentication-required'`
    (`reauthentication-required`): the two meanings of a `401` on a call that needs a recent
    sign-in, told apart by the header. Without the header (an application that resets responses)
    such a `401` gives `'sign-in-required'`, which a call's reason union therefore also includes;
    `me()` then tells the two apart.
  - `'cancelled'`: the person dismissed the browser's passkey prompt (`NotAllowedError`).
  - `'not-found'`, `'rejected'`, `'refused'`, `'conflict'`, `'none-pending'` as listed below.

### Calls

Grouped by feature, matching who's paths. Statuses are who's, from the guides and `who.ts`.

| Call | Endpoint | Value | Reasons |
|---|---|---|---|
| `me()` | `GET /me` | `{ identityId, permissions }` | `'signed-out'` (401) |
| `signOut()` | `POST /logout` | `void` | (none) |
| `sessions.list()` | `GET /sessions` | `Session[]` | `'signed-out'` |
| `sessions.end(id)` | `DELETE /sessions/{id}` | `void` | `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'`, `'not-found'` |
| `sessions.endOthers()` | `DELETE /sessions?others=true` | `{ ended: number }` | `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'` |
| `email.send(email)` | `POST /email/send` | `void` (always 202) | (none) |
| `email.signIn({ token } \| { email, code })` | `POST /email/sign-in` | `{ identityId }` | `'rejected'` (401) |
| `email.signInFromLocation()` | as above | result, or `null` when the URL has no `#token=` | as above |
| `email.addAddress(address, replaces?)` | `POST /email/addresses` | `void` (202) | `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'`, `'not-found'` |
| `email.confirmAddress({ token } \| { code })` | `POST /email/addresses/confirm` | `void` | `'rejected'` (400), `'conflict'` (409), `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'` |
| `email.confirmAddressFromLocation()` | as above | result, or `null` without `#confirm-address=` | as above |
| `passkeys.supported()` | (none) | `boolean` | (none) |
| `passkeys.register(label)` | `POST /passkey/register/options`, `/register` | `void` | `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'`, `'rejected'` (400), `'cancelled'` |
| `passkeys.signIn()` | `POST /passkey/sign-in/options`, `/sign-in` | `{ identityId }` | `'rejected'` (401), `'cancelled'` |
| `passkeys.remove(credentialId)` | `DELETE /passkey/register/{id}` | `void` | `'refused'` (403), `'signed-out'` |
| `oidc.authorizeUrl(registrationId)` | (builds the URL) | `string` | (none) |
| `oidc.pending()` | `GET /oidc/pending` | `PendingSignUp` | `'none-pending'` (404) |
| `oidc.signUp()` | `POST /oidc/sign-up` | `{ identityId }` | `'refused'` (403), `'none-pending'` |
| `oidc.link()` | `POST /oidc/link` | `void` | `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'`, `'conflict'` (409), `'none-pending'` |
| `oidc.startLink(registrationId)` | `POST /oidc/link/{id}` | navigates away | `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'`, `'not-found'` |

- **CSRF** is handled inside the client: before an unsafe request, if there is no `XSRF-TOKEN`
  cookie, it makes one `GET <baseUrl>/me` to get one; it reads the cookie fresh on every request;
  after `signOut()` it fetches a new one. The application never sees CSRF.
- **Passkeys** carry the base64url conversion both ways, and the `excludeCredentials` /
  `allowCredentials` descriptors, as the spa's `who.ts` does today.
- **The `FromLocation` helpers** remove the token from the URL with `history.replaceState` before
  any request, whatever the outcome.
- **Provider sign-in** stays a full-page navigation: `authorizeUrl` gives the link for a button;
  `startLink` asks who for the provider URL as JSON and then sets `location`.
- `Session` and `PendingSignUp` types match today's `who.ts`.

### Building it in Maven

- `who-client/pom.xml`, packaging `pom`, a module of the root reactor ordered before
  `who-examples`. Its build runs, through `frontend-maven-plugin`: install Node, `npm ci`, the
  format check, lint, the typecheck, the tests, and the build. A failure in any fails the Maven
  build, so `./mvnw -Pci clean install` covers the client.
- It is never deployed to Maven Central: `maven.deploy.skip` (and the `-pl` exclusion list in the
  publish workflow if that is still how examples are excluded).

### The examples

- **spa:** `"@jwcarman/who-client": "file:../../../who-client"` in its `package.json`;
  `src/who.ts` is deleted; components call a client made once in a small module.
- **web-app, web-and-api, observability:** the build copies `who-client/dist/index.js` into each
  one's `target/classes/static/js/who-client.js`; pages `import { createWhoClient } from
  '/js/who-client.js'`; `static/js/who.js` is deleted. Page scripts change only where calls'
  answers changed shape (results instead of status numbers).
- Each example still works end to end: sign in with an email code and link, passkey register,
  sign in and remove, provider sign-in where the example has it, sessions, sign out.

### Publishing

- The release workflow (`maven-publish.yml`) gains the roux shape:
  - a `verify` job: the tag is a plain version; build the client and rehearse
    `npm publish --dry-run`;
  - an `npm` job (needs `verify`): `id-token: write`, npm 11.5.1 or later checked, `npm version
    "$RELEASE_TAG" --no-git-tag-version --allow-same-version`, build, `npm publish --access
    public`; provenance is automatic with trusted publishing;
  - the existing Maven job then `needs: [verify, npm]`, so an npm failure never strands a Maven
    Central release.
  - Actions pinned by commit SHA, as in the existing workflows.
- **Bootstrap, done once by the owner by hand:** npm trusted publishing can be set up only for a
  package that exists. The owner builds the client from the branch, runs
  `npm version 0.0.1 --no-git-tag-version`, `npm publish --access public` from `who-client/`
  (not committing the version change), then on npmjs.com adds this repository's
  `maven-publish.yml` as the package's trusted publisher. After the first real release, `0.0.1` is
  deprecated with `npm deprecate`. The plan spells out each step.

### Documentation

- A new guide, `docs/guides/client.md`: install (npm, or jsDelivr for a page without a bundler),
  creating a client, results and `unwrap`, `decodeError` with Spring's `ProblemDetail`, every call,
  the same-origin cookie constraint, and the `Who-Error` fallback.
- Each feature guide gets one line pointing at the client's call for that feature.
- `CHANGELOG.md` Added entry; the cutting-a-release notes mention the npm job.

## Left to the builder

- File layout inside `who-client/src` (one module per feature group is expected).
- Exact names of internal helpers, and how the result type's generics are spelled, as long as a
  caller's `detail` type flows from `decodeError`.
- The interface's exact name and package, and whether the two `ResponseStatusException` failures
  in `SessionsFilter` get who exceptions of their own (with codes) or stay status-only.
- Ordering: the `Who-Error` server change lands first (it is a Java change with its own tests), and
  the client is built on it.
- The test split: msw-faked server tests for each call's statuses, plus unit tests for the
  base64url conversion and `baseUrl` validation; whether to add Stryker as roux does.
- Whether the format, lint and typecheck run inside the Maven module or also in a separate CI
  job, as long as `-Pci` runs them.
- How the no-bundler examples get the file (resources plugin copy or another plugin), as long as
  nothing built is committed.
