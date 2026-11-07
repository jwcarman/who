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

**In:** the two credential modules; browser-session sign-in and sign-out; JSON sign-in endpoints;
database rate limiting; `TIMESTAMPTZ` for every who table; Liquibase XML changelogs for every module;
native support (hints and generated metadata shipped in the jars); a new example app with end-to-end
tests run on the JVM and natively; the native spike rebuilt on who.

**Out:** a typed sign-in code (designed, later); invitations and roles UI (apps build on
`who-enrollment` and `who-rbac` as they are); several tenants in one JVM (see 8); admin recovery
tooling (see 8).

**Delivered as three plans, in order, each ending shippable and reviewed:**

1. Sessions, `who-email-link`, `/who/me`, sign-out, rate limits, `TIMESTAMPTZ`, Liquibase.
2. `who-passkey`.
3. Native support, `who-example-passwordless` with JVM and native end-to-end tests, the spike on who.

## 1. Decisions

| Topic | Decision |
|---|---|
| Spring | Spring Boot 4.1.2 / Spring Security 7.1 (already on `main`) |
| Version | `0.11.0-SNAPSHOT`; CI sets the release version from the tag |
| Secrets | Every token value comes from `Secrets.token()` (256 bits, URL-safe Base64). Only its SHA-256 hash is stored |
| Email-link sign-in | who's own endpoints, token store and authentication. Spring's `oneTimeTokenLogin()` is not used, so its `/ott/generate` and `/login/ott` endpoints never exist (D3) |
| Passkeys | Spring Security WebAuthn (`spring-security-webauthn`, `webauthn4j-core` 0.32.0.RELEASE); who supplies the stores and the authentication |
| Session principal | Re-resolved from the database on every request (D1) |
| Configurer | `WhoWebSecurity.passwordless()` returns an `AbstractHttpConfigurer`, the same pattern as `WhoApiKeySecurity.apiKey()` |
| CSRF | Required. Cookie token for the SPA, rotated at sign-in; the sign-in POSTs are protected too (D2) |
| Rate limits | Counters in a who table; client IP from Spring's forwarded-header support only (D4) |
| Timestamps | `TIMESTAMPTZ` in every who table, new and existing |
| Tables | All `who_`-prefixed: `who_email_credential`, `who_email_link_token`, `who_rate_limit`, `who_passkey_user`, `who_passkey_credential` |
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

- registers the sign-in endpoints of whichever credential modules are on the classpath, and permits
  them;
- answers `401` with JSON (not a redirect) to unauthenticated requests;
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

**`POST /who/sign-out`** invalidates the session, clears the CSRF cookie, and answers `204`.

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

**Tokens (D3).** `who_email_link_token(token_hash varchar(64) pk, credential_id uuid fk cascade,
expires_at timestamptz)`. A token value comes from `Secrets.token()`; only its SHA-256 hash is
stored. It expires after `who.email-link.token-ttl` (default 5 minutes). Consuming is one atomic
statement:

```sql
DELETE FROM who_email_link_token
 WHERE token_hash = :hash AND expires_at > :now
RETURNING credential_id
```

so two concurrent confirmations cannot both succeed. Expired rows are deleted by a scheduled sweep
(`who.email-link.sweep-interval`, default 1 hour).

**Requesting a link.** `POST /who/email-link` with JSON `{"email": "..."}`:

- always answers `202` with an empty body, whether or not the address is known (no enumeration);
- the lookup, token creation and sending run asynchronously after the response, so the response
  time does not reveal whether the address is known (D6);
- only for a known address with an `ACTIVE` identity, a token is created and
  `EmailLinkSender.send(address, link)` is called;
- `link` is `who.email-link.link-base-url` + `#token=` + the token value. The token is in the URL
  fragment, which browsers never send to a server or in a `Referer` header, so it stays out of
  access logs (D6). The app's page reads the fragment and confirms it (below); a mail scanner
  fetching the link does nothing;
- rate limits apply before anything is queued (section 5); above them the answer is `429`.

`EmailLinkSender` is an SPI. `MailEmailLinkSender` (when `JavaMailSender` is present) sends a plain
text email from `who.email-link.from`, subject `who.email-link.subject`.

**Confirming.** `POST /who/email-link/confirm` with JSON `{"token": "..."}`:

- consumes the token, loads its credential, and resolves the principal through `WhoService`;
- on success the session is established (section 2) and the answer is `200` with
  `{"identityId": "..."}`;
- an unknown, used or expired token, or an identity that is not `ACTIVE`, is `401` with
  `{"error": "invalid_token"}`. Every cause gets the same answer.

## 4. `who-passkey` (plan 2)

**Stores.** who implements Spring Security's two WebAuthn repositories on its own tables:

- `JdbcWhoPasskeyUserRepository implements PublicKeyCredentialUserEntityRepository`, on
  `who_passkey_user(id varchar(1000) pk, identity_id uuid unique fk cascade, name, display_name)`.
  The user handle (`id`) is 64 random bytes, not derived from the identity or the email address.
  `name` is the identity id. `display_name` is the identity's email address when it has one; it is
  stored in the user's synced credential manager, which the spec accepts and documents (D5).
- `JdbcWhoPasskeyCredentialRepository implements UserCredentialRepository`, on
  `who_passkey_credential` (Spring's columns, plus `credential_uuid uuid`). Each passkey is a
  `PasskeyCredential(UUID id)` linked to the identity through `CredentialIdentityRepository`.
- `PasskeyService.revoke(UUID credentialId)` deletes the `who_passkey_credential` row and the link
  together (D5).

**Registering** (signed in only): Spring's `POST /webauthn/register/options` and
`POST /webauthn/register`, keyed by the signed-in identity.

- Registration requires recent authentication: the session's sign-in happened within
  `who.passkey.registration-window` (default 10 minutes). Otherwise the answer is `401` with
  `{"error": "reauthentication_required"}`, and the app sends the user through an email link again.
  Registering while signed out is `401` (D5).
- User verification is `required`, both at registration and at sign-in. A passkey is the only
  factor, so it must prove the user is present and verified (D5).
- After a passkey is added, the `PasskeyAddedNotifier` SPI is called. Its default implementation
  (when `JavaMailSender` is present) emails the identity's address, if it has one, so an owner
  notices a passkey they did not add (D5).

**Signing in:** Spring's `POST /webauthn/authenticate/options` and `POST /login/webauthn`. who's
authentication resolves the passkey's user entity to its identity and, through `WhoService`, to the
principal; the session is then established (section 2). Success answers `200` with
`{"identityId": "..."}`, failure `401`.

**Configuration:** `who.passkey.rp-id`, `who.passkey.rp-name`, `who.passkey.allowed-origins`,
`who.passkey.registration-window`. The `rp-id` is the app's own host. Apps must not share a
registrable domain as their `rp-id`, or every app's passkeys appear in every other app's picker (D5).

## 5. Rate limits (plan 1, D4)

`POST /who/email-link` is limited per address and per client IP: at most
`who.email-link.rate-limit.per-address` and `…per-ip` requests within `…window` (defaults 5, 20,
15 minutes).

- **Counters live in the database** (`who_rate_limit(bucket varchar(400), window_start timestamptz,
  count int, primary key (bucket, window_start))`), incremented with one upsert, so the limits hold
  across instances and restarts. Old windows are deleted by the same sweep as expired tokens.
- **Client IP** is `HttpServletRequest.getRemoteAddr()`. who never reads `X-Forwarded-For` itself.
  Behind a load balancer, the app sets `server.forward-headers-strategy=native` (or `framework`) and
  its trusted proxies, so Spring replaces the remote address with the real client's. The docs say so
  plainly: without it, every user shares the proxy's address and the per-IP limit blocks them all.
- **Known cost:** the per-address limit lets anyone exhaust a victim's sign-in emails for one window.
  The docs state it; passkeys are not rate limited by this and still work.

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
  - `/who/me` and sign-out;
  - a role-protected endpoint;
  - rate limiting;
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
  - rate-limit windows (per address, per IP, window reset);
  - the passkey stores (Spring's repository contracts) and the random user handle;
  - hint predicates for every registered family.
- **Integration** (Testcontainers PostgreSQL):
  - both sign-ins end with a session whose principal is re-resolved on the next request;
  - suspending the identity, removing a role and revoking the credential each take effect on the
    next request;
  - two concurrent confirmations of one token: exactly one succeeds;
  - no enumeration (the same `202` for known and unknown addresses);
  - `429` over each limit, counted across two application contexts sharing one database;
  - sign-in POSTs without a CSRF token are `403`, and `passwordless()` on a chain with CSRF disabled
    fails at startup;
  - registering a passkey while signed out, or outside the registration window, is `401`;
  - user verification is required;
  - revoking a passkey removes its row;
  - the changelogs match `schema.sql`.
- **End to end:** the example app's Playwright tests, on the JVM and natively.
- **Existing:** all 179 current tests still pass.
