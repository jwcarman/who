# who: passwordless credentials (design)

Date: 2026-11-07 (revised after the 0.10.0 security release)
Status: revised spec, pending review
Branch: `passwordless` (local, on top of 0.10.0)
Target: who 0.11.0
Evidence: the native-image spike, `~/IdeaProjects/native-spike` (its README holds the findings);
the 0.10.0 security review's design findings D1–D7, all resolved below.

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
who's Liquibase changelog, and gets, with no security code of its own:

- sign-in by email link and by passkey, from its own (React) UI, through JSON endpoints;
- a browser session whose principal is a `WhoPrincipal` that is current on every request, so
  `@RequiresPermission` works as it does for JWT and API-key callers, and a suspended identity or a
  removed role takes effect at once;
- all of it working as a native image, proven by end-to-end tests run against the native binary.

## Scope

**In:** the two credential modules; browser-session sign-in and sign-out through Spring Security's
own endpoints, answering JSON;
database rate limiting; `TIMESTAMPTZ` for every who table; Liquibase XML changelogs for every module;
native support (hints and generated metadata shipped in the jars); a new example app with end-to-end
tests run on the JVM and natively; the native spike rebuilt on who.

**Out:** a typed sign-in code (plan 4, below); invitations and roles UI (apps build on
`who-enrollment` and `who-rbac` as they are); several tenants in one JVM (see 8); admin recovery
tooling (see 8).

**Delivered as three plans, in order, each ending shippable and reviewed:**

1. Sessions, `who-email-link` (with its resend limit), `/who/me`, `/logout`, `TIMESTAMPTZ`, Liquibase.
2. `who-passkey`.
3. Native support, `who-example-passwordless` with JVM and native end-to-end tests, the spike on who.
4. A typed sign-in code in the same email as the link ("or enter K7F-2QM"), for new devices,
   mobile in-app browsers and link-scanning mail security. Digits or alphanumeric (Crockford
   Base32, case-insensitive, grouped), with a configurable length. A code is low-entropy, so it
   has an attempt limit, is bound to the browser session that requested it, and each address has
   at most one live code. Designed in full when plan 4 is written.

## 1. Decisions

| Topic | Decision |
|---|---|
| Spring | Spring Boot 4.1.2 / Spring Security 7.1 (already on `main`) |
| Version | `0.11.0-SNAPSHOT`; CI sets the release version from the tag |
| Secrets | Every token value comes from `Secrets.token()` (256 bits, URL-safe Base64). Only its SHA-256 hash is stored |
| Spring-native | who uses Spring Security's own endpoints and extension points wherever they exist, and supplies only stores, authentication and handlers. Its only endpoint of its own is `GET /who/me` |
| Email-link sign-in | Spring Security's one-time-token login (`/ott/generate`, `/login/ott`), configured through its hooks: who's token service, request resolver, converter, provider and JSON handlers (D3) |
| Sign-out | Spring Security's `/logout`, answering `204` |
| Passkeys | Spring Security WebAuthn (`spring-security-webauthn`, `webauthn4j-core` 0.32.0.RELEASE); who supplies the stores and the authentication |
| Session principal | Re-resolved from the database on every request (D1) |
| Configurer | `WhoWebSecurity.passwordless()` returns an `AbstractHttpConfigurer`, the same pattern as `WhoApiKeySecurity.apiKey()` |
| CSRF | Required. Cookie token for the SPA, rotated at sign-in; the sign-in POSTs are protected too (D2) |
| Rate limits | who limits only what it alone can see: at most one sign-in email per address per `resend-interval`, enforced by the token row itself. Per-IP limiting and flood protection belong upstream, at the ingress (D4) |
| Timestamps | `TIMESTAMPTZ` in every who table, new and existing |
| Tables | All `who_`-prefixed: `who_email_credential`, `who_email_link_token`, `who_passkey_user`, `who_passkey_credential` |
| Schema | Each module keeps its `schema.sql` and adds `db/changelog.xml` (Liquibase XML), equivalent to it |
| Responses | Sign-in endpoints answer JSON and status codes, never redirects |
| Native | Hints for data-dependent type families, plus tracing-agent metadata from end-to-end tests, shipped in each module's jar |

## 2. Sessions and the security chain (plan 1)

`who-spring-security` gains `WhoWebSecurity.passwordless()`, an `AbstractHttpConfigurer` that an app
applies to its own browser `SecurityFilterChain`:

```java
http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
```

It:

- turns on Spring Security's `oneTimeTokenLogin()` and `webAuthn()` for whichever credential
  modules are on the classpath, with who's hooks (sections 3 and 4), and permits their endpoints;
- configures Spring's `logout()` to answer `204` and clear the CSRF cookie;
- answers a plain `401` (Spring's `HttpStatusEntryPoint`, not a redirect) to unauthenticated
  requests;
- sets CSRF to `CookieCsrfTokenRepository.withHttpOnlyFalse()` with `CsrfTokenRequestAttributeHandler`
  (the SPA sends `X-XSRF-TOKEN` from the cookie). The sign-in POSTs require the token too, so a
  hostile page cannot sign a victim in to the attacker's account. The token is rotated at sign-in;
- fails at startup with a clear message if the chain disables CSRF or is `STATELESS`. A session
  principal in a chain without CSRF protection makes every endpoint in it forgeable (D2). An app
  that also serves stateless `/api/**` callers keeps them in a separate chain;
- changes the session id at sign-in. The session cookie is `SameSite=Lax`, `HttpOnly`, and `Secure`
  outside local development (who documents the Boot properties; it does not override the app's);
- adds `Referrer-Policy: no-referrer` to its responses.

**Fresh principal on every request (D1).** At sign-in, the session stores only the identity id and
the credential id. On every request, a filter loads the identity and its permissions through
`WhoService` (one or two small queries) and puts a fresh `WhoAuthenticationToken` in the context.
If the identity is no longer `ACTIVE`, or the credential is no longer linked to it, the filter
invalidates the session and the request continues unauthenticated (`401` on a protected endpoint).
Role and permission changes therefore take effect on the next request.

**`GET /who/me`** (any signed-in caller) answers `{"identityId", "permissions"}`, from the fresh
principal.

JWT and API-key callers are unaffected; their chains stay stateless.

## 3. `who-email-link` (plan 1)

**Credential.** `EmailCredential(UUID id, String address)` implements `Credential`. `address` is
trimmed and lower-cased, and unique. It is linked to an identity through the existing
`CredentialIdentityRepository`. Table `who_email_credential(id uuid pk, address varchar(320) unique)`.

`EmailCredentialService`:

- `EmailCredential register(Identity identity, String address)`: creates and links; fails if the
  address is taken. The Javadoc states the contract: call it only with an address the caller has
  verified (for example, one that received an invitation) (D6);
- `Optional<EmailCredential> findByAddress(String address)`, normalized the same way;
- `void revoke(UUID credentialId)`: deletes the credential and its link.

**Tokens (D3).** `who_email_link_token(credential_id uuid pk fk cascade, token_hash varchar(64)
unique, created_at timestamptz, expires_at timestamptz)`. Each address holds at most one live token,
so only the newest link works. A token value comes from `Secrets.token()`; only its SHA-256 hash is
stored. It expires after `who.email-link.token-ttl` (default 5 minutes).

Issuing is one atomic upsert, which is also the resend limit (section 5):

```sql
INSERT INTO who_email_link_token (credential_id, token_hash, created_at, expires_at)
VALUES (:credentialId, :hash, :now, :expiresAt)
ON CONFLICT (credential_id) DO UPDATE
   SET token_hash = EXCLUDED.token_hash,
       created_at = EXCLUDED.created_at,
       expires_at = EXCLUDED.expires_at
 WHERE who_email_link_token.created_at <= :now - :resendInterval
```

One row changed means "send this link". No row changed means a link went out within the resend
interval, so nothing is sent. Consuming is one atomic statement:

```sql
DELETE FROM who_email_link_token
 WHERE token_hash = :hash AND expires_at > :now
RETURNING credential_id
```

so two concurrent confirmations cannot both succeed. The table holds at most one row per email
credential, so it cannot grow without bound and needs no sweep: an expired row stays until the next
request for that address replaces it.

**How it maps onto Spring.** `passwordless()` configures `oneTimeTokenLogin()` with:

| Hook | who's part |
|---|---|
| `tokenService` | `WhoOneTimeTokenService`: `generate` only creates a random value and touches no table; `consume` is the atomic delete above |
| `generateRequestResolver` | reads `{"email": "..."}` from the JSON body |
| `tokenGenerationSuccessHandler` | answers `202`, then, asynchronously, looks up the address, issues the token (the upsert above) and sends the link |
| `authenticationConverter` | reads `{"token": "..."}` from the JSON body |
| `authenticationProvider` | resolves the token's credential to a `WhoAuthenticationToken` |
| `authenticationSuccessHandler` / `authenticationFailureHandler` | JSON `200` / `401` |
| `showDefaultSubmitPage(false)` | no generated HTML page |

The endpoint paths stay Spring's defaults.

**Requesting a link.** `POST /ott/generate` with JSON `{"email": "..."}`:

- always answers `202` with an empty body, whether or not the address is known (no enumeration);
- the request thread does no lookup and no write: Spring's filter asks the token service for a
  token, which only generates a random value, and the handler answers `202` at once. The response
  time therefore cannot reveal whether the address is known (D6);
- after the response, the handler looks up the address. For an unknown address, or one whose
  identity is not `ACTIVE`, it stops. Otherwise it issues the token with the upsert above and, if a
  row changed, calls `EmailLinkSender.send(address, link)`;
- `link` is `who.email-link.link-base-url` + `#token=` + the token value. The token is in the URL
  fragment, which browsers never send to a server or in a `Referer` header, so it stays out of
  access logs (D6). The app's page reads the fragment and confirms it (below); a mail scanner
  fetching the link does nothing;
- the answer is `202` even when the resend interval suppresses the email (section 5).

`EmailLinkSender` is an SPI. `MailEmailLinkSender` (when `JavaMailSender` is present) sends a plain
text email from `who.email-link.from`, subject `who.email-link.subject`.

**Confirming.** `POST /login/ott` with JSON `{"token": "..."}`:

- consumes the token, loads its credential, and resolves the principal through `WhoService`;
- on success the session is established (section 2) and the answer is `200` with
  `{"identityId": "..."}`;
- an unknown, used or expired token, or an identity that is not `ACTIVE`, is `401` with
  `{"error": "invalid_token"}`. Every cause gets the same answer.

## 4. `who-passkey` (plan 2)

who uses Spring Security's `webAuthn()` and its endpoints, at their default paths:
`POST /webauthn/register/options`, `POST /webauthn/register`, `DELETE /webauthn/register/{id}`,
`POST /webauthn/authenticate/options` and `POST /login/webauthn`. Spring Security 7.1.2 fixes
several collaborators in place, so who supplies them through Spring's own hooks (the evidence for
each is in the plan 2 research notes):

- By default the sign-in filter stores the session with `HttpSessionSecurityContextRepository` and
  uses `NullAuthenticatedSessionStrategy`, so it neither changes the session id nor rotates CSRF.
  who replaces both.
- The configurer requires a `UserDetailsService` bean, and looks up the two repositories as shared
  objects first, then as beans. If it finds two beans of one type, it silently falls back to
  in-memory maps.
- User verification and the relying-party settings can be customized only through a
  `WebAuthnRelyingPartyOperations` bean. When that bean exists, the configurer's own `rpId`,
  `rpName` and origins are ignored.

**Wiring.** `PasskeyLoginMethod implements PasswordlessLoginMethod` (applied by `passwordless()`,
like email links):

- shares who's two repositories with the chain as `http` shared objects, so no bean lookup is
  involved;
- if the app has no `UserDetailsService`, autoconfiguration supplies one that finds no users, only
  so the configurer can start. who's sign-in never calls it;
- calls `webAuthn(w -> w.disableDefaultRegistrationPage(true))`;
- post-processes `WebAuthnAuthenticationFilter` (`withObjectPostProcessor`) to set who's
  `AuthenticationManager` (below), `WhoSessionSecurityContextRepository`, the chain's shared
  `SessionAuthenticationStrategy` (so the session id changes and CSRF rotates at sign-in), and JSON
  success and failure handlers;
- adds `PasskeyRegistrationGuardFilter` after `ExceptionTranslationFilter`, for the answers Spring
  has no hook for (Registering, below).

`WhoAuthenticationToken.getName()` returns the identity id. Spring keys registration, its options
and deletion on `Authentication.getName()`, which must be stable.

**Relying party.** who publishes a `WebAuthnRelyingPartyOperations` bean: a decorator over Spring's
`Webauthn4JRelyingPartyOperations`, built from `who.passkey.rp-id`, `rp-name` and
`allowed-origins`. It:

- requires user verification at registration (`setCustomizeCreationOptions`, with a resident key
  required) and at sign-in (`setCustomizeRequestOptions`). Spring then enforces user verification
  when it verifies the response. A passkey is the only factor, so it must prove the user is present
  and verified (D5);
- creates who's user entity before delegating, so the user handle is who's (below), not the
  32-byte one Spring would create;
- calls `PasskeyAddedNotifier` after a credential is registered.

**Stores.** who implements Spring's two repository interfaces on its own tables. Spring's JDBC
classes are final and hard-code their table names. who's columns mirror Spring's, with ids as
base64url text as in Spring:

- `JdbcWhoPasskeyUserRepository implements PublicKeyCredentialUserEntityRepository`, on
  `who_passkey_user(id varchar(1000) pk, identity_id uuid unique fk cascade, name varchar(100) not
  null, display_name varchar(200))`:
  - The user handle (`id`) is 64 random bytes, not derived from the identity or the email address.
    Sign-in maps by user handle, never by name.
  - `name` is what the passkey picker shows: the identity's email address when it has one,
    otherwise the identity id. `display_name` is the same.
  - `findByUsername(name)` looks the entity up by identity id, because Spring passes
    `Authentication.getName()`.
  - The email address is stored in the user's synced credential manager; the spec accepts and
    documents this (D5).
- `JdbcWhoPasskeyCredentialRepository implements UserCredentialRepository`, on
  `who_passkey_credential`: Spring's columns (including `attestation_object`, which sign-in needs,
  and `label not null`, so the registration request must include a label), plus
  `credential_uuid uuid`. Each passkey is a `PasskeyCredential(UUID id)` linked to the identity
  through `CredentialIdentityRepository`.
- Revoking deletes the `who_passkey_credential` row and the identity link together (D5), whether it
  happens through Spring's `DELETE /webauthn/register/{id}` (whose `delete` who implements) or
  through `PasskeyService.revoke(UUID credentialId)`.

**Registering** (signed in only):

- Registering requires recent authentication: the session's sign-in happened within
  `who.passkey.registration-window` (default 10 minutes, measured from `WhoSession.signedInAt`).
  Otherwise the answer is `401` with `{"error": "reauthentication_required"}`, and the app sends the
  user through an email link again.
- Registering while signed out is `401`; Spring alone would answer `400` (D5).
- A response that fails verification is `400` with `{"error": "invalid_registration"}`; Spring alone
  would answer `500`.
- Success is Spring's own `200` with `{"success": true}`.
- After a passkey is added, the `PasskeyAddedNotifier` SPI is called. Its default implementation
  (when `JavaMailSender` is present) emails the identity's address, if it has one, so an owner
  notices a passkey they did not add (D5).

**Signing in:** `POST /webauthn/authenticate/options`, then `POST /login/webauthn`. who's
`AuthenticationProvider`:

- verifies through the relying-party operations;
- takes the verified credential id from the request's public key (`getRawId()`) and maps it to
  `credential_uuid`;
- resolves the principal through `WhoService` and returns `WhoAuthenticationToken(principal,
  credentialUuid)`.

The session is then established (section 2). Success answers `200` with `{"identityId": "..."}`.
Every failure answers `401` with `{"error": "invalid_credential"}`.

**Configuration:** `who.passkey.rp-id`, `who.passkey.rp-name`, `who.passkey.allowed-origins`,
`who.passkey.registration-window`. Like email links, passkeys switch on only when
`who.passkey.rp-id` is set. The `rp-id` is the app's own host. Apps must not share a registrable
domain as their `rp-id`, or every app's passkeys appear in every other app's picker (D5).

**Dependency:** `spring-security-webauthn` plus `webauthn4j-core` pinned to `0.32.0.RELEASE` (Spring
7.1.2 declares 0.31.10.RELEASE; the API Spring uses is compatible).

## 5. Rate limits (plan 1, D4)

Spring Security has no rate limiting, and limiting well across a cluster is hard. who limits only
what it alone can see, and leaves the rest upstream.

- **In who: one sign-in email per address per `who.email-link.resend-interval` (default 60
  seconds).** The address is inside the JSON body, so only the app can limit by it. The limit is the
  token row itself: the upsert in section 3 replaces a token only once the interval has passed.
  PostgreSQL serializes the upsert on that row, so two instances cannot both send. who needs no
  counter table, no windows and no extra sweep. This protects a person's inbox from email bombing
  and the app's sending reputation. The worst an attacker can do to a victim is delay their sign-in
  email by one interval; passkey sign-in is unaffected.
- **Upstream: per-IP limits and flood protection.** The load balancer, WAF or ingress sees the real
  client IP and stops floods before they reach the app. who never reads `X-Forwarded-For`. The docs
  say plainly that `/ott/generate` and `/login/ott` need per-IP limits at the ingress, with an nginx
  example. On the noir platform, the platform's ingress provides them for every customer app.

## 6. Timestamps

Every who table uses `TIMESTAMPTZ`: the new tables, and the existing `who_identity` and
`who_enrollment_token` columns. Repositories bind `Instant` values as `OffsetDateTime` in UTC, so
values no longer depend on the JVM's time zone. Existing databases need an `ALTER TABLE … TYPE
timestamptz USING … AT TIME ZONE '<the zone the JVM used>'`; the 0.11.0 upgrade guide gives it.

## 7. Liquibase (plan 1)

Every module adds `org/jwcarman/who/<module>/db/changelog.xml`, equivalent to its `schema.sql`.
`who-autoconfigure` adds `org/jwcarman/who/db/changelog-all.xml`, which includes every module's
changelog present on the classpath in a safe order (`who-jdbc` first). An app includes that one
file from its own master changelog and sets `who.initialize-schema: never`. A test applies both the
changelogs and the `schema.sql` files to PostgreSQL and compares the resulting schemas.

## 8. Tenancy and recovery (D7)

- **Tenancy.** Each customer app has its own database, which isolates tenants. who assumes one
  tenant per JVM. If one JVM ever serves several tenant databases, the token store, rate-limit
  counters and `WhoService` must become tenant-scoped; that is out of scope and the docs say so.
- **Recovery.** An email link signs in, so control of the mailbox is control of the identity: the
  mailbox is the root of trust, and the docs say so. A user who loses the mailbox needs an
  administrator: `EmailCredentialService.revoke` plus `register` with a new, verified address.
  Admin tooling for this is out of scope.

## 9. Native (plan 3)

- **Hints in each jar.** `who-passkey` registers whole the type families chosen by the incoming
  data: every class of `com.webauthn4j.data.attestation.authenticator`, `…attestation.statement`,
  `com.webauthn4j.data.extension`, `com.webauthn4j.data.client`, Spring's
  `org.springframework.security.web.webauthn.jackson` and `…webauthn.management`,
  and Spring's `org.springframework.security.web.webauthn.api`. `who-autoconfigure` registers the
  changelogs and schema files as resources.
- **Generated metadata.** The tracing agent runs over the example app's end-to-end tests;
  `scripts/native-metadata.sh` writes the result, split by package, into each module's
  `META-INF/native-image/org.jwcarman.who/<module>/reachability-metadata.json`.
- **`who-example-passwordless`**: a minimal web app (an owner seeded with an email credential and the
  OWNER role) with Playwright tests:
  - email-link sign-in;
  - passkey registration and sign-in with P-256 and with Ed25519 keys (Chrome's virtual
    authenticator);
  - `/who/me` and `/logout`;
  - a role-protected endpoint;
  - the resend interval (a second request within it sends no email);
  - a used link and an expired link;
  - a suspended identity losing access on its next request.

  They run against the JVM build and against the native binary.

## 10. The spike (plan 3)

`~/IdeaProjects/native-spike` drops its hand-built security and depends on who: the owner becomes
an identity with an email credential and the OWNER role, sign-in goes through who's endpoints, and
its probe and Playwright tests pass on the JVM and natively.

## Testing

- **Unit:**
  - address normalization and uniqueness;
  - token generation and hashing, expiry, single use;
  - the resend interval (within it nothing is sent; after it a new token replaces the old one, and
    the old link stops working);
  - the passkey stores (Spring's repository contracts) and the random user handle;
  - hint predicates for every registered family.
- **Integration** (Testcontainers PostgreSQL):
  - both sign-ins end with a session whose principal is re-resolved on the next request;
  - suspending the identity, removing a role and revoking the credential each take effect on the
    next request;
  - two concurrent confirmations of one token: exactly one succeeds;
  - no enumeration (the same `202` for known and unknown addresses);
  - two concurrent link requests for one address, from two application contexts sharing one
    database: exactly one email is sent;
  - sign-in POSTs without a CSRF token are `403`, and `passwordless()` on a chain with CSRF disabled
    fails at startup;
  - registering a passkey while signed out, or outside the registration window, is `401`;
  - user verification is required;
  - revoking a passkey removes its row;
  - the changelogs match `schema.sql`.
- **End to end:** the example app's Playwright tests, on the JVM and natively.
- **Existing:** all 179 current tests still pass.
