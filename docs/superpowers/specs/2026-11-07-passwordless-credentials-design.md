# who: passwordless credentials (design)

Date: 2026-11-07
Status: design agreed in conversation; written spec pending review
Branch: `passwordless` (local; nothing pushed or released)
Evidence: the native-image spike, `~/IdeaProjects/native-spike` (its README holds the findings).

## Why

who separates an identity from the credentials that prove it. Today the credentials are JWTs and
API keys, both for machine callers that send a token on every request. The products built on the
noir platform need people to sign in to web apps without passwords:

- **email sign-in links**, for the first sign-in, a new device and recovery;
- **passkeys**, for everyday sign-in.

Both are credentials in exactly who's sense: one identity can have an email address and several
passkeys (phone, laptop), and every one of them resolves to the same identity and its roles. This
work adds them to who, so that who becomes the identity layer of every customer app, which also
must run as a GraalVM native image.

### Success

An app depends on `who-spring-boot-starter`, `who-email-link`, `who-passkey` and `who-rbac`, includes
who's Liquibase changelogs, and gets, with no security code of its own:

- sign-in by email link and by passkey, from its own (React) UI, through JSON endpoints;
- a browser session whose principal is the `WhoPrincipal`, so `@PreAuthorize` works as it does for
  JWT and API-key callers;
- all of it working as a native image, proven by end-to-end tests run against the native binary.

## Scope

**In:** the two credential modules; browser-session sign-in; JSON-friendly sign-in endpoints;
Liquibase XML changelogs for every module; native support (hints and generated metadata shipped in
the jars); a new example app with end-to-end tests run on the JVM and natively; Spring Boot 4.1.1;
local version `0.10.0-SNAPSHOT`; the native spike rebuilt on who.

**Out:** a typed sign-in code (designed, later); releasing to Maven Central; removing the Ralph-era
files (`PRD.md`, `progress.txt`, `ralph-loop.sh`, `specs/`); changing the public API of existing
modules (additions only); invitations and roles UI (apps and noir-app-security build on
`who-enrollment` and `who-rbac` as they are).

## 1. Decisions

| Topic | Decision |
|---|---|
| Spring | Spring Boot 4.1.1 / Spring Security 7.1 (from 4.0.3) |
| Email-link sign-in | Spring Security one-time-token login; who supplies the token store and the authentication |
| Passkeys | Spring Security WebAuthn (`spring-security-webauthn`, `webauthn4j-core` 0.32.0.RELEASE); who supplies the stores and the authentication |
| Tables | All `who_`-prefixed: `who_email_credential`, `who_one_time_token`, `who_passkey_user`, `who_passkey_credential` |
| Schema | Each module keeps its `schema.sql` and adds `db/changelog.xml` (Liquibase XML), equivalent to it |
| Session | After either sign-in, the session's `Authentication` is a `WhoAuthenticationToken` (principal `WhoPrincipal`) |
| Responses | Sign-in endpoints answer JSON and status codes, never redirects |
| Native | Hints for data-dependent type families, plus tracing-agent metadata from end-to-end tests, shipped in each module's jar |
| Version | `0.10.0-SNAPSHOT` (CI still sets the release version from the tag) |

## 2. `who-email-link`

**Credential.** `EmailCredential(UUID id, String address)` implements `Credential`. `address` is
trimmed and lower-cased, and unique. It is linked to an identity through the existing
`CredentialIdentityRepository`. Table `who_email_credential(id uuid pk, address varchar(320) unique)`.

`EmailCredentialService`:
- `EmailCredential register(Identity identity, String address)`: creates and links; fails if the
  address is taken.
- `Optional<EmailCredential> findByAddress(String address)`, normalized the same way.

**Tokens.** `JdbcWhoOneTimeTokenService implements OneTimeTokenService`, on
`who_one_time_token(token_value varchar(64) pk, address varchar(320), expires_at timestamp)`: a
256-bit random token, URL-safe Base64, expiring after `who.email-link.token-ttl` (default 5 minutes),
deleted when consumed. An expired token is deleted and rejected.

**Requesting a link.** `POST /who/email-link` with JSON `{"email": "..."}`:
- always answers `202` with an empty body, whether or not the address is known (no enumeration);
- only for a known address, a token is created and `EmailLinkSender.send(address, link)` is called;
- `link` is `who.email-link.link-base-url` + `?token=` + the token. The app's own page reads the token
  and confirms it (below); a scanner fetching the link does nothing;
- rate limits: at most `who.email-link.rate-limit.per-address` requests per address and
  `…per-ip` per client IP within `…window` (defaults 5, 20, 15 minutes); above them the answer is
  `429`, and nothing is sent.

`EmailLinkSender` is an SPI. `MailEmailLinkSender` (when `JavaMailSender` is present) sends a plain
text email from `who.email-link.from`, subject `who.email-link.subject`.

**Confirming.** `POST /who/email-link/confirm` with JSON `{"token": "..."}`:
- consumes the token; resolves its address to the `EmailCredential`, and through `WhoService` to the
  `WhoPrincipal`;
- on success, the session holds a `WhoAuthenticationToken` (the session id changes), and the answer
  is `200` with `{"identityId": "..."}`;
- an unknown, used or expired token, or a disabled identity, is `401` with
  `{"error": "invalid_token"}`.

## 3. `who-passkey`

**Stores.** who implements Spring Security's two WebAuthn repositories on its own tables:
- `JdbcWhoPasskeyUserRepository implements PublicKeyCredentialUserEntityRepository`, on
  `who_passkey_user(id varchar(1000) pk, identity_id uuid unique fk, name, display_name)`. The user
  entity's `name` is the identity id; `display_name` is the identity's email address when it has
  one.
- `JdbcWhoPasskeyCredentialRepository implements UserCredentialRepository`, on
  `who_passkey_credential` (Spring's columns, plus `credential_uuid uuid`). Each passkey is a
  `PasskeyCredential(UUID id)` linked to the identity through `CredentialIdentityRepository`, so a
  passkey can be revoked like any credential.

**Registering** (signed in only): Spring's endpoints, `POST /webauthn/register/options` and
`POST /webauthn/register`, keyed by the signed-in identity. Registering while signed out is `401`.

**Signing in:** Spring's `POST /webauthn/authenticate/options` and `POST /login/webauthn`. who's
authentication resolves the passkey's user entity to its identity and, through `WhoService`, to the
`WhoPrincipal`; the session then holds a `WhoAuthenticationToken`. Success answers `200` with
`{"identityId": "..."}`, failure `401`.

**Configuration:** `who.passkey.rp-id`, `who.passkey.rp-name`, `who.passkey.allowed-origins`.

## 4. Sessions and the security chain

`who-spring-security` gains `WhoWebSecurity`, a `Customizer<HttpSecurity>` that an app applies to
its own `SecurityFilterChain`:

```java
http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
```

It enables whichever of the two modules is on the classpath, permits the sign-in endpoints,
installs the JSON success and failure handlers, keeps CSRF on (the SPA sends the `X-XSRF-TOKEN`
header from the cookie), and answers `401` (not a redirect) to unauthenticated requests. JWT and
API-key filters are unaffected; an app may use both kinds in one chain.

`GET /who/me` (any signed-in caller) answers `{"identityId", "roles", "permissions"}`.

## 5. Liquibase

Every module adds `org/jwcarman/who/<module>/db/changelog.xml`, equivalent to its `schema.sql`,
and `who-autoconfigure` adds `org/jwcarman/who/db/changelog-all.xml`, which includes every module's
changelog present on the classpath order-safely (`who-jdbc` first). An app includes that one file
from its own master changelog and sets `who.initialize-schema: never`.

## 6. Native

- **Hints in each jar.** `who-passkey` registers whole the type families chosen by the incoming data:
  every class of `com.webauthn4j.data.attestation.authenticator`, `…attestation.statement`, and
  Spring's `org.springframework.security.web.webauthn.api`. `who-autoconfigure` registers the
  changelogs and schema files as resources.
- **Generated metadata.** The tracing agent runs over the example app's end-to-end tests;
  `scripts/native-metadata.sh` writes the result, split by package, into each module's
  `META-INF/native-image/org.jwcarman.who/<module>/reachability-metadata.json`.
- **`who-example-passwordless`**: a minimal web app (owner seeded with an email credential and the
  OWNER role) with Playwright tests: email-link sign-in; passkey registration and sign-in with P-256
  and with Ed25519 keys (Chrome's virtual authenticator); `who/me`; a role-protected endpoint; rate
  limiting; a used and an expired link. They run against the JVM build and against the native binary.

## 7. The spike

`~/IdeaProjects/native-spike` drops its hand-built security and depends on who: the owner becomes
an identity with an email credential and the OWNER role, sign-in goes through who's endpoints, and
its probe and Playwright tests pass on the JVM and natively.

## Testing

- **Unit:** address normalization and uniqueness; token generation, expiry and single use; rate
  limits (per address, per IP, window reset); the passkey stores (Spring's repository contracts);
  hint predicates for every registered family; changelogs equivalent to `schema.sql` (both applied to
  Postgres, then the schemas compared).
- **Integration** (Testcontainers Postgres): both sign-ins end with a `WhoAuthenticationToken` in the
  session; `@PreAuthorize` sees roles and permissions; no enumeration (same `202`); `429` over the
  limits; registering a passkey while signed out is `401`; a suspended identity cannot sign in.
- **End to end:** the example app's Playwright tests, on the JVM and natively.
- **Existing:** all 140 current tests still pass.
