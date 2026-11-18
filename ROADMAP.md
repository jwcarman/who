# Roadmap

What we're looking at next. These are themes, not promises, and none is tied to a release: order
and scope change as people use who and tell us what they need. Every feature starts with a design
doc in `docs/superpowers/specs/` before any code. What has shipped is in the
[CHANGELOG](CHANGELOG.md).

## Next

- **More identity providers.** Multi-tenant Microsoft Entra ID (`common` and `organizations`), with
  a tenant allow-list.
- **Sign-up through `WhoSignUpPolicy` for email links.**

## Later

- **Organizations.** Roles and permissions scoped to an organization ("admin in Acme, member in
  Globex"), invitations to an organization, and permission checks against the current one. This
  changes the data model more than anything else here, so its design should start early, even if
  the code comes last.
- **Stability, for 1.0.**
  - A semantic-versioning promise and a deprecation policy, enforced in CI with an API
    compatibility check (japicmp or revapi).
  - Fuzzing through ClusterFuzzLite, if the weekly Jazzer workflow isn't enough: fuzzing on pull
    requests and a shared corpus.
  - The OpenSSF Best Practices badge.
  - Javadoc for every public type.
  - Smaller constructors: the classes with eight or more collaborators (`IdentitiesController`,
    `EmailAddressesController`, `PasskeyLoginMethod`, `EmailLinkLoginMethod`) split up.

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
- Sign in with Apple directly: its cross-site `form_post` callback, signed client secret and
  one-time name need an Apple-only design, and it can be tested only on a public HTTPS domain.
  Apple can still reach who through a broker, such as Auth0 or Keycloak, as an ordinary OpenID
  Connect provider.
- An Actuator `info` contributor listing the sign-in methods: it would tell anyone who can read
  `/actuator/info` how the application can be attacked. Each chain logs its methods at startup
  instead.

## Already in place

A security policy with private reporting, a security model guide, a CycloneDX
SBOM and build provenance for every release, CodeQL, an OpenSSF Scorecard, hash-pinned
workflow dependencies, contributing guidelines, and Jazzer fuzz tests of the inputs who parses from
untrusted callers (sign-in bodies, typed codes, addresses, the API-key header), replayed in every
build and fuzzed weekly.
