# Security events

Target: who 0.17.0 (the first part of the observability theme in ROADMAP.md). Reviewed once
against the code (Fable); the decisions below include that review's findings.

## Why

who decides who gets in and what changes about an identity, but it tells no one. Security
standards (for example OWASP ASVS, chapter 7) expect every sign-in, every failed sign-in with its
reason, and every change to credentials and permissions to be recorded. An application built on
who cannot do that today, because only who knows *why* a sign-in failed: by design every failure
is the same `401`. A security review of such an application would flag the missing audit trail,
and the application's developers could not fix it from outside.

Events are also the base for what the roadmap plans next: security notifications, an API key's
last-used time, metrics, and alerts on attacks such as code guessing.

## Goals

- who publishes a **public, stable** event for every sign-in (success and failure, every method),
  sign-out, ended session, sign-in email, and every change to identities, credentials and roles.
- A failed sign-in event carries a **server-side reason**. The client's answer does not change:
  still the same uniform `401`.
- Applications receive them as ordinary Spring application events (`@EventListener`, `@Async`).
- API keys sign in through Spring's authentication manager, like every other method.
- `who-core` stays free of Spring at runtime.
- One DEBUG log line for each failed sign-in, with its reason.

## Non-goals (this release)

- Metrics, new tracing spans, the Actuator info contributor. Later, built on these events.
- Storing events. who publishes; the application decides whether to keep an audit log.
- Request details (IP address, user agent) and the acting identity as event fields. Listeners run
  on the request thread and read them there (see Listeners).
- Changing what clients see, apart from the API-key change below.

## Anti-goals (failure even if it works)

- **An event that leaks to the client**: a reason in a response or header.
- **A listener that changes who's behavior**: turns a `401` into a `500`, aborts a sign-in, or
  rolls back a change who made.
- **Breaking applications that construct who's services themselves** without an upgrade path.
- **Attacker-supplied text reaching listeners unchecked.**

## Constraints

- `who-core` needs no Spring at runtime (`WhoService` uses `@Transactional` only when present).
- Modules (`who-email-link`, `who-passkey`, `who-apikey`, `who-jwt`, `who-enrollment`, `who-rbac`)
  publish through the same interface. No module has a `module-info.java`.
- Many paths run with no transaction: every sign-in (the code-attempt count must commit even when
  a sign-in fails), `EmailLinkIssuer.issue`, `WhoService.createIdentity`, `ApiKeyService.create`,
  `WhoEnrollmentService.createToken`, passkey registration.
- Native image must keep working (noir-app-template's e2e suite).
- Project rules in CONTRIBUTING.md: no suppressed warnings, explicit imports, tests at the level a
  user sees them, docs and CHANGELOG with every change.

## Approaches considered

- **A. who's own event types plus a small publishing interface (chosen).** `who-core` defines a
  one-method `WhoEventPublisher`; services call it; the autoconfiguration adapts it to Spring.
  Every module and sign-in method reports the same way, and core stays Spring-free.
- **B. Extend Spring Security's sign-in events**, A for everything else. Rejected: two styles of
  event, and Spring's types don't cover JWT credential changes or who's reasons. (Spring's own
  `AuthenticationSuccessEvent` still fires for email, passkey and, after this change, API-key
  sign-ins; the docs tell readers to use who's events.)
- **C. A who-specific listener interface instead of Spring events.** Rejected: applications lose
  Spring's event machinery.

Decided with the maintainer after review:

- **Listeners run after commit** (option a). Failing closed (an audit failure aborting the change)
  was rejected: sign-ins have no transaction to abort, and a broken listener would block admin
  work.
- **No actor or request fields** in events; listeners read them from the request.
- **API keys move to Spring's authentication manager**, with the behavior change below.

## Design

### Publishing

- `org.jwcarman.who.core.event.WhoEventPublisher`: `void publish(WhoEvent event)`, and
  `WhoEventPublisher.NOOP`.
- `WhoEvent`: a plain (not sealed) interface with `Instant occurredAt()`. Every event is a record.
  Not sealed, because events live in several modules' packages and new event types must not break
  applications' code.
- Each event type lives in the module that publishes it (core events in
  `org.jwcarman.who.core.event`, email events in `org.jwcarman.who.emaillink`, and so on), so
  `who-core` never depends on a module's types such as `EmailLinkOutcome`.
- **After commit.** The autoconfiguration's adapter publishes each event through Spring's
  `ApplicationEventPublisher`. If a transaction is active, it registers a transaction
  synchronization and publishes in `afterCommit`; a rolled-back change publishes nothing. With no
  transaction, it publishes at once. Either way a listener runs after who's work is final, so it
  cannot roll it back.
- **Exceptions.** The adapter catches any exception from publishing, logs it at WARN (exception
  class and event type, no personal data) and carries on, so a listener never changes who's
  answer. The docs say that Spring stops at the first listener that throws, so listeners should
  handle their own failures.
- **Ordering on the sign-in path.** A sign-in's events are published after its response is
  decided, so no listener can change it.

### Sign-in

| Event | Fields |
|-------|--------|
| `SignInSucceeded` | method, identityId, credentialId |
| `SignInFailed` | method, reason, address (nullable), identityId (nullable), credentialId (nullable) |

`method`: `EMAIL_LINK`, `EMAIL_CODE`, `PASSKEY`, `API_KEY`, `JWT`.

`SignInSucceeded` is published only once the sign-in is complete: for session-based methods,
after the session is saved (from who's success handler, not from the provider). API keys and JWTs
authenticate every request and publish `SignInSucceeded` on every request, as Spring does for
bearer tokens; listeners filter by method.

Reasons, only as fine as each path can tell **without an extra database call**:

- email link: `MALFORMED`, `INVALID_LINK` (unknown, used or expired: one atomic consume),
  `INACTIVE_IDENTITY`;
- email code: `MALFORMED`, `NO_LIVE_CODE` (unknown address, no code, expired, attempts used up),
  `WRONG_CODE`, `CODE_ALREADY_USED` (the right code lost the race to a concurrent use or expiry),
  `INACTIVE_IDENTITY`;
- passkey: `INVALID_ASSERTION` (signature, origin, unknown credential: Spring's verification
  cannot tell these apart without more lookups), `INACTIVE_IDENTITY`;
- API key: `UNKNOWN_KEY`, `INACTIVE_IDENTITY`;
- JWT: `MISSING_CLAIMS`, `UNKNOWN_CREDENTIAL`, `INACTIVE_IDENTITY`. JWTs Spring rejects before
  who sees them (signature, expiry) are reported by Spring's own events, not who's.

`INACTIVE_IDENTITY` needs `WhoService` to say why resolving failed. A new method returns a result
that distinguishes "not linked", "no such identity" and "inactive" (with the identity id); the
existing `resolve` stays and delegates to it.

**Address.** `SignInFailed.address` is set only when the address passed `EmailAddresses.isSafe`
(bounded, no control characters). Otherwise it is null and the reason is `MALFORMED`.

### Sessions

- `SignedOut` (identityId, credentialId) when a who session signs out.
- `SessionEnded` (identityId, credentialId, reason: `IDENTITY_INACTIVE`, `CREDENTIAL_REVOKED`) when
  `WhoSessionSecurityContextRepository` drops a session whose identity or credential no longer
  resolves.

### Sign-in email (`who-email-link`)

`SignInEmailRequested` (address or null, outcome: `EmailLinkOutcome`, identityId nullable),
published by `EmailLinkIssuer` on the email thread after the `202`. Same address rule as above.

### Identities (`WhoService`)

`IdentityCreated`, `IdentityDeactivated`, `IdentityReactivated`, `IdentityDeleted` (identityId),
`OrphanedCredentialsPurged` (count). Only on a real change: status changes become a conditional
update ("set inactive where active") whose row count decides whether to publish, so concurrent
calls publish once.

### Credentials

`CredentialAdded` and `CredentialRevoked`: identityId, credentialId, kind (`EMAIL`, `PASSKEY`,
`API_KEY`, `JWT`), and a label where one exists (address, passkey label, API key name).

Every way a credential appears or disappears publishes, wherever the change actually happens:

- `EmailCredentialService.register` and `revoke`; an orphaned credential that `register` replaces
  publishes `CredentialRevoked`;
- passkey registration (`WhoRelyingPartyOperations`) and every passkey removal, including Spring's
  `DELETE /webauthn/register/{id}`, which reaches `JdbcWhoPasskeyCredentialRepository.delete`
  directly: publish there, not only in `PasskeyService`;
- `ApiKeyService.create` and `revoke`;
- JWT credentials: they have no service today, only `JwtCredentialRepository.save` and
  `deleteById`; this release adds a small `JwtCredentialService` (link, unlink) that publishes,
  and the docs point to it;
- `WhoEnrollmentService.enroll` adds a credential: it publishes `CredentialAdded` with the kind
  and label it can know (the builder decides how `enroll` learns the kind; the credential id alone
  does not say);
- `WhoService.deleteIdentity` publishes `CredentialRevoked` for each credential it removes;
  `CredentialRemover` gains what that needs (kind, label) in a compatible way.

Revoking needs the identity id, so revokes look it up before unlinking.

### Enrollment

`EnrollmentTokenCreated`, `EnrollmentTokenRevoked` (identityId, token id; never the value), also
for the pending tokens `createToken` revokes. Enrollment uses a `Clock` instead of `Instant.now()`.

### Roles (`RbacService`)

`RoleCreated`, `RoleDeleted`, `RolePermissionAdded`, `RolePermissionRemoved`, `RoleAssigned`,
`RoleUnassigned`.

### Passkey registration refused

`PasskeyRegistrationRefused` (identityId, reason: `REAUTHENTICATION_REQUIRED`) when the
registration guard refuses an add because the sign-in is too old.

### API keys through the authentication manager

- An `ApiKeyAuthenticationProvider` resolves a key (hash lookup, then the identity); a converter
  reads the header; Spring's `AuthenticationFilter` ties them together, with an authentication
  manager built the way `PasskeyLoginMethod` builds one (event publisher and observation
  post-processor applied).
- **Behavior change (breaking):** a request whose header carries an unknown or revoked key, or a
  key of an inactive identity, now gets `401` at once, even on endpoints that allow anonymous
  access. Before, it continued as anonymous. No header, or a blank one, behaves as before.
- How applications add API keys to a chain stays as close to today's as possible; the upgrading
  guide covers any change.

### Never in any event

Link tokens, codes, API key values, enrollment token values, hashes.

### Personal data

`SignInFailed`, `SignInEmailRequested` and email `CredentialAdded`/`Revoked` carry the email
address. Deliberate: "40 failed codes for alice@…" is the most useful attack signal. The docs mark
these as personal data. who's own logs never write an address above DEBUG.

### Listeners

The docs show how a listener gets what events leave out: the acting identity from
`SecurityContextHolder`, and the client from `RequestContextHolder`, both available because
listeners run on the request thread. `SignInEmailRequested` runs on the email thread, where the
request is gone (with a `ContextPropagatingTaskDecorator`, trace context still is).

Synchronous listeners run on the sign-in path, so a slow listener slows sign-in, and work that
differs by reason could make failures take different times. The docs recommend `@Async` for
anything slow.

### Logging

- One DEBUG line per failed sign-in: method, reason, identity or credential id when known; the
  address only at DEBUG.
- The security model guide states the rule: addresses, codes, tokens and keys never appear in
  who's logs at INFO or above.

### Compatibility

- Services gain a `WhoEventPublisher` (and a `Clock` where missing). Existing public constructors
  stay and use `WhoEventPublisher.NOOP`.
- Events are public API from this release. Policy, in the docs: new event types and new enum
  values may appear in a minor release, so listeners must tolerate unknown values; removing or
  renaming an event or field is breaking, with an upgrade note.
- Breaking: the API-key `401` change, and anything the API-key wiring change requires.

### Docs

- A new "Events" guide: every event, its module and when it fires, after-commit delivery,
  exceptions, personal data, how to read actor and client, and an audit-log example.
- The API Keys guide: the new wiring and the `401` behavior.
- The security model guide: the audit trail and the logging rule.
- CHANGELOG and the upgrading guide.

## Left to the builder

- Exact names of events, fields and enum constants, within the sets above.
- How `enroll` learns a credential's kind, and the shape of the new resolve result.
- How the API-key filter is wired into chains, as long as applications' existing setup changes as
  little as possible.
- Tests per CONTRIBUTING.md: each event proven where a user sees it (an HTTP sign-in, a service
  call) with a recording listener; a throwing listener shown not to change any answer or roll back
  any change; a rolled-back change shown to publish nothing.
