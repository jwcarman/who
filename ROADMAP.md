# Roadmap

Where who is going after 0.19.0. Each release below is a theme, not a promise: order and scope
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
- **Actuator.** Moved to 0.18, then dropped: see the startup log under 0.20.

Events, meter names and observation names are stable public API from 0.17.0. The failure reason
is a tag.

## 0.18: sensitive actions and self-service accounts

- **Opt-in sign-in methods (breaking).** (Done in 0.18.0: see [Upgrading to
  0.18.0](docs/guides/upgrading.md#upgrading-to-0180).) `who-spring-boot-starter` brings the base;
  applications add `who-email-link`, `who-passkey`, `who-apikey` or `who-jwt`, and each turns on
  with its jar, with `who.<module>.enabled=false` to turn it off. With no chain of its own, an
  application gets who's default chain with every method on the classpath.
- **Sign in with an identity provider.** (Done in 0.18.0: see [Social
  Login](docs/guides/social-login.md).) Google, GitHub and any OpenID Connect provider through
  `who-oidc`, on Spring's `oauth2Login`, sharing the `(issuer, subject)` credential with JWT
  sign-in (`JwtCredential` became `FederatedCredential`). Sign-up is closed until a
  `WhoSignUpPolicy` opens it, and linking is an explicit, confirmed step. Never by email address.
- **Recent sign-in (step-up).** (Done in 0.19.0: `who.security.recent-sign-in` guards
  adding a passkey, a provider account or an email address since 0.18.0, and
  `@RequiresRecentSignIn` guards your own actions since 0.19.0: see [Recent
  sign-in](docs/guides/security-model.md#recent-sign-in).) Require a sign-in within the last N minutes before adding a
  passkey, changing an address, creating an API key or deleting an account, for example
  `@RequiresRecentSignIn(minutes = 5)` beside `@RequiresPermission`.
- **Changing an email address,** confirmed by a link or code sent to the new address. (Done in
  0.18.0: the old address is told of the change.)
- **Sessions.** (Done in 0.19.0: see [Sessions](docs/guides/sessions.md#see-and-end-sessions).) List an identity's sessions (device, last seen) and sign out one of them, beside
  today's "sign out everywhere".
- **Security notifications** (moved to 0.19; a changed address is notified since 0.18.0) for a
  changed address or a new API key, as `PasskeyAddedNotifier`
  does for passkeys. Built on the 0.17 events.
- **API keys that are ready for production.** (Done in 0.18.0, moved from 0.19: see [API
  Keys](docs/guides/api-keys.md).) Every key expires and records its last use; a key holds only
  its scopes, never more than its owner has now; keys rotate with an overlap and carry a
  recognizable prefix and checksum for secret scanners.

## 0.20: more identity providers

0.19.0 shipped sessions, recent sign-in, one endpoint root and the TypeScript client instead, so
these moved to 0.20.

- **Startup log of sign-in methods.** (Done: each chain logs the methods it accepts at startup;
  see [Observability](docs/guides/observability.md#startup-log).) An Actuator `info` contributor
  was dropped: it would tell anyone who can read `/actuator/info` how the application can be
  attacked.
- Sign in with Apple. Its callback is a cross-site `form_post`, which needs a CSRF-exempt callback
  or a cookie-backed authorization-request store.
- **Human check.** (Done: `WhoHumanCheck` guards the email endpoints, and `who-turnstile` adds
  Cloudflare Turnstile; see [Email Links](docs/guides/email-links.md#human-check).)
- Multi-tenant Microsoft Entra ID (`common` and `organizations`), with a tenant allow-list.
- Sign-up through `WhoSignUpPolicy` for email links.

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
- Javadoc for every public type.

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
