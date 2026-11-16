# One root for every who endpoint

## Why

who's HTTP surface is scattered. Its own endpoints sit under `/who`, but the sign-in endpoints
use whatever Spring Security's configurers default to: `/logout`, `/ott/generate`, `/login/ott`,
`/webauthn/...`, `/login/webauthn`, `/oauth2/authorization/{id}`, `/login/oauth2/code/{id}`. An
application has to know Spring's naming to find them, reserve five unrelated prefixes it cannot
use itself, and proxy or route all of them. The owner wants every endpoint under one root the
application can choose.

## Goals

- Every endpoint who serves lives under one root, `who.base-path`, defaulting to `/who`.
- Under the root, paths are grouped by feature, matching who's module names (`email`,
  `passkey`, `oidc`) and the `/who/...` paths that already exist.
- One place computes every path, so a filter, a public-path rule, a CSRF exemption and the docs
  cannot disagree.

## Non-goals

- A property per endpoint. One root only; a per-endpoint override can come later if a real need
  shows up.
- Keeping the old paths working. who has two users; breaking changes are fine when documented
  (CHANGELOG and `docs/guides/upgrading.md`), with no compatibility shims.
- Moving Spring Boot's `/error`. It is the application's, not who's.

## Anti-goals

- An old path that still answers. After this change `/logout`, `/login/ott`, `/webauthn/...` and
  the rest reach no who filter at all.
- Remapping requests (rewriting `/who/logout` to `/logout` before Spring Security sees it). Every
  guard, CSRF rule, log line and event must see the path the browser used.
- A path that escapes the root, including one a configurer adds by default.

## The paths

Relative to the root; with the default root every one starts `/who`.

| Today                                | Becomes                       | Method |
|--------------------------------------|-------------------------------|--------|
| `/who/me`                            | `/me`                         | GET    |
| `/who/sessions`, `/who/sessions/{id}`| `/sessions`, `/sessions/{id}` | as now |
| `/who/email/addresses`, `.../confirm`| `/email/addresses`, `.../confirm` | as now |
| `/who/oidc/pending`, `/sign-up`, `/link`, `/link/{registrationId}` | same under the root | as now |
| `/logout`                            | `/logout`                     | POST   |
| `/ott/generate`                      | `/email/send`                 | POST   |
| `/login/ott`                         | `/email/sign-in`              | POST   |
| `/webauthn/register/options`         | `/passkey/register/options`   | POST   |
| `/webauthn/register`                 | `/passkey/register`           | POST   |
| `/webauthn/register/{id}`            | `/passkey/register/{id}`      | DELETE |
| `/webauthn/authenticate/options`     | `/passkey/sign-in/options`    | POST   |
| `/login/webauthn`                    | `/passkey/sign-in`            | POST   |
| `/oauth2/authorization/{id}`         | `/oidc/authorize/{id}`        | GET    |
| `/login/oauth2/code/{id}`            | `/oidc/callback/{id}`         | GET    |

The public paths (reachable signed out) are the same endpoints as today under their new names:
`/email/send`, `/email/sign-in`, `/passkey/sign-in/options`, `/passkey/sign-in`,
`/oidc/authorize/**`, `/oidc/callback/**`, plus `/error`.

## Approaches

- **A. One property and a shared path helper (chosen).** `who.base-path` feeds a `WhoPaths`
  bean in who-spring-security that turns a relative path into a full one. Every module takes its
  paths from it. One place decides, so tests can assert the whole list.
- B. A root plus a property per endpoint, the way Spring does it. About fifteen properties to
  document and validate for flexibility nobody asked for. Rejected for now.
- C. A servlet-level rewrite to Spring's defaults. Least code, but old paths keep answering
  unless also blocked, and every guard and log sees a path the browser never sent. Rejected: in a
  security library that mismatch is where bugs hide.

The owner chose A, and chose feature-grouped names over Spring's names with a prefix.

## Decided

- **The property.** `who.base-path`, default `/who`. Startup fails, naming the property and the
  value, when it does not start with `/`, ends with `/`, is `/` alone, or contains `*`, `{`, `}`,
  `?`, `#`, `;` or whitespace. *(The exact rejection list is my call.)*
- **Email links.** Spring's `oneTimeTokenLogin()` stays; who sets `tokenGeneratingUrl` and
  `loginProcessingUrl`. The link in the email still points at the application's own page
  (unchanged); only the POSTs move.
- **Sign-out.** `logoutUrl` set from the root on browser chains.
- **Provider sign-in.** `oauth2Login()` stays. who sets the authorization endpoint's base URI
  (who's own `OidcAuthorizationRequests` resolver uses it too) and the redirection endpoint's.
  The callback URI each provider sees becomes `{baseUrl}<root>/oidc/callback/{registrationId}`.
  A registration left at Spring Boot's default redirect URI
  (`{baseUrl}/{action}/oauth2/code/{registrationId}`) gets who's callback without any setting. A
  registration with an explicit `redirect-uri` that does not end in who's callback path fails
  startup, naming the registration and the path it must use. *(My call: an explicit redirect URI
  who cannot serve would otherwise fail only at the provider, as a confusing mismatch error.)*
  Every application must update the redirect URI registered at each provider; the upgrade guide
  says so first.
- **Passkeys.** who uses Spring's four passkey filters as they are, but adds them to the chain
  itself instead of calling `webAuthn()`. `webAuthn()` hard-codes three of the four paths and
  post-processes only the sign-in filter, so mapping its filters after the fact needed a
  `BeanPostProcessor` that could fail open (old paths live, outside the registration guard)
  whenever it did not see the chain. *(Revised 2026-11-16 after the whole-branch review; the
  owner chose this over the post-processor.)*
  - who creates `WebAuthnAuthenticationFilter`, `WebAuthnRegistrationFilter`,
    `PublicKeyCredentialCreationOptionsFilter` and `PublicKeyCredentialRequestOptionsFilter`
    with Spring's public constructors, sets each matcher from `WhoPaths` through Spring's own
    setters, and adds them at the positions `webAuthn()` uses. Deleting a passkey keeps
    Spring's owner check, `CredentialRecordOwnerAuthorizationManager`.
  - This is wiring, not rebuilding: the challenges, verification and owner check stay Spring's.
    It drops two workarounds `webAuthn()` forced (a stub `UserDetailsService` and sharing
    who's repositories through the chain) and `webAuthn()`'s redirect to `/login` for a missing
    passkey factor.
  - The cost: a Spring release that changes what `webAuthn()` wires is not picked up by itself.
    A drift test builds a chain with Spring's `webAuthn()` and one with who's passkey method and
    fails if their sets of WebAuthn filter types differ.
  - An application's own `webAuthn()` chain is untouched: who never calls `webAuthn()`.

  `PasskeyRegistrationGuardFilter` guards the new paths. Passkeys already registered keep
  working: they are bound to the domain, not the path.
- **who's own endpoints.** The filters for sessions, email addresses and pending provider
  sign-ups take their paths from `WhoPaths`. Their public `*_URL` constants go away (a breaking
  change, documented). `/me` is the only Spring MVC endpoint and maps through
  `${who.base-path:/who}/me`.
- **Public paths and CSRF.** `PasswordlessLoginMethod.publicPaths()` returns full paths built
  from `WhoPaths`, so `WhoPublicPaths` and the CSRF exemptions need no change in shape.
- **Examples and docs.** All five examples move to the new paths, with their tests. The SPA's
  dev proxy narrows to the one root. The guides list each module's endpoints by their new names.
  `upgrading.md` gets an old-to-new table, with the provider redirect URI first.

## How we know it works

- One end-to-end test runs an application with a non-default root (`/auth`) and every module on.
  For each row of the table it checks that the new path reaches who: a sign-in method answers,
  or who's filter refuses with its own error, not a plain `404`. It also checks that each old
  path reaches no who filter.
- The provider tests run a full sign-in against the stub provider through `/oidc/authorize` and
  `/oidc/callback`, and check that the `redirect_uri` sent matches.
- A passkey registration, sign-in and delete run end to end on the new paths.
- `who.base-path` validation has a test for each rejected form.
- The existing suites pass with the default root after their paths are updated.

## Left to the builder

- The shape of `WhoPaths` (`of(String)` or one method per endpoint), and how sign-in methods
  receive it.
- Whether the provider callback is set by rewriting the registrations or by customizing the
  authorization request. Either works, as long as the authorization request and the token
  exchange send the same `redirect_uri`.
- How the passkey filters reach the chain's session strategy, which is shared only once
  session management is set up.
