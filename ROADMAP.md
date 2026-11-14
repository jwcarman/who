# Roadmap

Where who is going after 0.16.0. Each release below is a theme, not a promise: order and scope
change as people use who and tell us what they need. Every feature starts with a design doc in
`docs/superpowers/specs/` before any code.

## 0.17: observability

who publishes what happens, so applications can measure, alert on and audit it.

- **Events.** (Done in 0.17.0: see the [Events guide](docs/guides/events.md).) Spring `ApplicationEvent`s for sign-in requested, succeeded and failed (with the
  method and a server-side reason: unknown address, wrong code, burnt code, expired, malformed),
  email sent or failed, credential added or revoked, identity deactivated or deleted, and role
  granted or revoked. The reason never reaches the client.
- **Metrics.** (Done in 0.17.0: see the [Observability guide](docs/guides/observability.md).)
  With a `MeterRegistry` present, counters built from those events, such as
  `who.sign-ins{method, outcome, reason}` and `who.sign-in.emails{outcome}`. Tags are bounded; no
  addresses or identity ids.
- **Tracing.** (Done in 0.17.0.) Micrometer `Observation`s around resolving a principal (every
  authenticated request) and issuing a sign-in email, including the mail send. Redeeming a code
  is already timed by Spring Security's `spring.security.authentications`.
- **Logging.** (Done in 0.17.0.) A DEBUG line with the reason for each failed sign-in, and a written rule that
  addresses, codes and tokens never appear at INFO or above.
- **Actuator.** Moved to 0.18: who cannot yet tell whether API-key and JWT sign-in are really in
  use, since they switch on from a class check alone.

Events, meter names and observation names are stable public API from 0.17.0. The failure reason
is a tag.

## 0.18: sensitive actions and self-service accounts

- **Opt-in sign-in methods (breaking).** Slim `who-spring-boot-starter` to what every application
  needs (core, autoconfigure, Spring Security, JDBC, RBAC); applications add `who-email-link`,
  `who-passkey`, `who-apikey` or `who-jwt` for the methods they use. Every method also needs an
  explicit switch, as email links and passkeys do today: API-key and JWT sign-in currently turn on
  whenever their classes are present, and the starter always brings them. First establish what an
  application that never configured them exposes today, for the upgrade notes. The native test
  apps already use this shape.
- **Actuator `info` contributor** naming the sign-in methods that are on, once each method has
  its explicit switch (moved from 0.17).
- **Recent sign-in (step-up).** Require a sign-in within the last N minutes before adding a
  passkey, changing an address, creating an API key or deleting an account, for example
  `@RequiresRecentSignIn(minutes = 5)` beside `@RequiresPermission`.
- **Changing an email address,** confirmed by a link or code sent to the new address.
- **Sessions.** List an identity's sessions (device, last seen) and sign out one of them, beside
  today's "sign out everywhere".
- **Security notifications** for a changed address or a new API key, as `PasskeyAddedNotifier`
  does for passkeys. Built on the 0.17 events.

## 0.19: API keys that are ready for production

- An expiry date, and a last-used time to find stale keys, recorded by the API-key sign-in itself
  (throttled), not from events: a valid key publishes no event.
- A recognizable prefix (such as `who_…`) so secret scanners can spot a leaked key.
- Scopes: a key holds a subset of its identity's permissions.
- Rotation with an overlap window.

## 0.20: sign in with an identity provider

- Google, Apple, GitHub and any OIDC provider as a `PasswordlessLoginMethod`, on Spring's
  `oauth2Login`, linked to a who identity the way email and passkeys are.
- Reuse the `(issuer, subject)` credential that JWT sign-in already keys on, so a browser sign-in
  and an API call from the same provider reach the same identity. Rename `JwtCredential` to fit
  both uses (a breaking change, made in this release). Providers without OIDC, such as GitHub,
  need a fixed issuer string by convention.
- Linking a provider account to an identity that already exists, and unlinking it, only as an
  explicit step by someone already signed in. Never link automatically by email address.
- Serialization hints for `oauth2Login`'s session state, proven with a native build.

## 0.21: organizations

- Roles and permissions scoped to an organization ("admin in Acme, member in Globex"),
  invitations to an organization, and permission checks against the current one.
- This changes the data model more than anything else here, so its design should start early,
  well before 0.21, even if the code comes last.

## 1.0: stability

- A semantic-versioning promise and a deprecation policy, enforced in CI with an API
  compatibility check (japicmp or revapi).
- Fuzzing through ClusterFuzzLite, if the weekly Jazzer workflow below isn't enough: fuzzing on
  pull requests and a shared corpus.
- The OpenSSF Best Practices badge.
- Javadoc for every public type (the build reports 77 gaps today, most in `who-example`).

Already in place: a security policy with private reporting, a security model guide, a CycloneDX
SBOM and build provenance for every release, CodeQL, an OpenSSF Scorecard, hash-pinned
workflow dependencies, contributing guidelines, and Jazzer fuzz tests of the inputs who parses from
untrusted callers (sign-in bodies, typed codes, addresses, the API-key header), replayed in every
build and fuzzed weekly.

## Under consideration

- Wildcard permissions (`task.*`) or permission hierarchies.
- Impersonation for support staff, with an audit trail.
- Optional built-in rate limiting for `/ott/generate` and `/login/ott`.
- Passkey storage on databases other than PostgreSQL.
- A guide to account recovery when someone has lost both their passkey and their email.
- WebFlux support.

## Not planned

- Passwords.
- SMS codes.
- Acting as an OAuth authorization server.
