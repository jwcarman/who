# Sign in with an identity provider (social login): design

Status: revised after Fable's security review; awaiting the owner's approval. Target release: 0.20
(ROADMAP "sign in with an identity provider").

## Intent

Let a who application offer "Sign in with Google / GitHub / any OIDC provider" in the browser,
with 0.18's seamless experience: add the jar, configure the provider the way Spring Boot already
does, and it works. Spring Security does the protocol in `oauth2Login`; who does what Spring cannot
know: which who identity a provider account belongs to, whether an unknown account may create
one, and the session, events and metrics who gives every other sign-in.

The owner: "I feel like that is pretty easy to support using spring security. I think they already
do all of that." And, on how: "We want to be more security first. We want to do things the right
way and the secure way." For social login signing in is signing up, so who must know the person
*intends* to create an account, and must never let a provider account reach an identity its owner
did not choose.

## Goals

- `who-oidc`, a new module. Adding the jar plus Boot's `spring.security.oauth2.client.registration.*`
  properties turns social sign-in on in who's default chain and in `WhoSecurity.all()` /
  `WhoWebSecurity.passwordless()`.
- A provider account is the credential `(issuer, subject)`, shared with JWT sign-in, so a browser
  sign-in and an API call from the same provider can reach the same identity.
- A known account signs in to a `WhoSession` through `WhoSessionSecurityContextRepository`, as
  email links and passkeys do.
- An unknown account creates nothing until the person confirms; the sign-up policy decides, and
  who's default policy refuses.
- A signed-in person links a provider account only by confirming it, seeing which account it is.
- Events, metrics, and a native-image proof, as for the other methods.

## Non-goals (0.20)

- **Apple.** Its callback is a cross-site `form_post`, which needs a CSRF-exempt callback or a
  cookie-backed authorization-request store, delicate enough for its own release. Deferred by the
  owner.
- Multi-tenant Microsoft Entra (`common` / `organizations` endpoints). Single-tenant Entra works;
  multi-tenant needs an issuer template or tenant allow-list, a later release.
- The existing-account hint (matching a verified email to an identity), the stored provider email,
  and `verified_at` on email credentials. Cut on Fable's advice, owner agreed: three schema changes
  and an enumeration surface for a convenience; the last-used cookie covers "which method did I
  use" on the same browser.
- Exchanging an ID token posted by a front end; keeping provider access or refresh tokens;
  SAML; connecting email-link sign-up to the policy (next release); reactive apps; merging
  identities.

## Anti-goals (a failure even if it works)

- Linking, signing in, or matching by email address.
- Trusting the issuer a token asserts about itself; the issuer comes from configuration.
- Open sign-up by default.
- Creating an identity, or linking an account, from a provider callback alone.
- Keeping Spring's `OAuth2AuthenticationToken`, `OidcUser` or the ID token in the session.
- Trusting an email address the provider has not marked verified, or any email from a provider not
  trusted for email.

## Constraints

- Spring Boot 4.1+, Spring Security 7.1, Java 25, PostgreSQL, as the rest of who.
- 0.18's module rules: jar present means on; `who.oidc.enabled=false` only turns it off; missing
  required configuration fails startup with a `WhoConfigurationException` naming what to set and
  the off switch. Every bean `@ConditionalOnMissingBean`.
- Browser methods require CSRF and sessions.
- Backward compatibility is not a constraint for this release (owner: "I'm not really worried
  about backward compatibility"); breaking changes go in the upgrade notes.
- Project code rules: no suppressed warnings, no star imports, snake_case `@Nested` JUnit classes.

## Approaches

1. **who's handler turns the `oauth2Login` result into a who session (owner's choice).**
2. Keep Spring's login in the session and translate per request: two principals, the ID token in
   the session, a live Spring session for an unlinked or deactivated account.
3. Exchange a front-end ID token: who would validate tokens itself. Deferred.

## Decided by the owner

- Approach 1; unknown accounts go to a sign-up policy; the default is a real refusing bean,
  `@ConditionalOnMissingBean`.
- Never link by email (ROADMAP); security first throughout.
- `JwtCredential` becomes `FederatedCredential`, in a new `who-federated` module.
- The table is renamed to `who_federated_credential` with an automatic migration; no compatibility
  view, no rolling-upgrade support.
- The existing-account hint is cut.
- `who-jwt` fails startup when tokens' audience is not validated.

## Design

### The credential (`who-federated`)

- `FederatedCredential(id, issuer, subject)`, `FederatedCredentialRepository`,
  `JdbcFederatedCredentialRepository`, `FederatedCredentialService` (`link`, `unlink`, as
  `JwtCredentialService` today), with its own autoconfiguration. `who-jwt` and `who-oidc` depend on
  it and not on each other, so adding `who-oidc` never turns JWT sign-in on. "Provider account" is
  the user-facing term.
- Table `who_jwt_credential` → `who_federated_credential`, same shape, `UNIQUE (issuer, subject)`:
  - Liquibase: `renameTable` guarded by `tableExists who_jwt_credential`; the `createTable`
    changeset keeps its identity when its file moves (`logicalFilePath` pinned).
  - Schema script: `ALTER TABLE IF EXISTS who_jwt_credential RENAME TO who_federated_credential;`
    before `CREATE TABLE IF NOT EXISTS who_federated_credential`.
  - Tests upgrade a 0.19 schema holding credentials on both paths, and those credentials still
    sign in.
- Whether `CredentialKind.JWT` becomes `FEDERATED` is the builder's call; the upgrade notes say
  either way.

### Which registrations sign in

- Every registration with the `authorization_code` grant. `client_credentials` and other grants
  are ignored, so an app's API-calling registrations are untouched.
- `who.oidc.providers.<registrationId>.enabled=false` excludes one.
- Activation checks for a `ClientRegistrationRepository` bean with at least one sign-in
  registration; none fails startup naming `spring.security.oauth2.client.registration` and
  `who.oidc.enabled=false`.

### The issuer comes from configuration (Fable C1)

Spring checks the ID token's `iss` only when the registration has `issuer-uri`. who does not rely
on that:

- **OIDC registrations** (scope `openid`): the issuer is the provider's `issuer-uri`, or
  `who.oidc.providers.<id>.issuer` when the provider is configured by endpoints. Neither set fails
  startup. who adds an ID-token validator (through Spring's `OidcIdTokenDecoderFactory` JWT
  validator factory) that rejects a token whose `iss` differs from the configured issuer, and the
  credential's issuer is always the configured one.
- **Non-OIDC registrations**: `who.oidc.providers.<id>.issuer` and `.subject-attribute`, both
  required, or a known default. who knows one: github.com, recognized by its endpoint host
  (`github.com` / `api.github.com`), not the registration id, with issuer `https://github.com` and
  subject attribute `id` (numeric, immutable). GitHub Enterprise gets no default. Boot's
  `user-name-attribute` is never used as the subject (apps set it to `login`).
- **Two sign-in registrations with the same issuer fail startup.**
- An issuer of Entra's `common` or `organizations` endpoints fails startup (multi-tenant is a
  non-goal).

### Sign-in flow (Fable I1, I7, I10)

`OidcLoginMethod implements PasswordlessLoginMethod` mounts `http.oauth2Login(...)`:

- Public paths: `/oauth2/authorization/**`, `/login/oauth2/code/**`, `GET /who/oidc/pending`,
  `POST /who/oidc/sign-up`.
- On the login filter, set only through the configurer, never as beans:
  - a `SecurityContextRepository` that saves nothing, so Spring's save of the
    `OAuth2AuthenticationToken` (which runs before the success handler) cannot remove the who
    session;
  - an `OAuth2AuthorizedClientRepository` that stores nothing, so an application's own
    `oauth2Client()` use keeps Boot's.
- Link requests started by a signed-in browser carry `prompt=select_account` to OIDC providers
  (an authorization-request resolver customizer), so the person chooses the account.
- The success handler:
  1. computes `(issuer, subject)` from configuration and the validated user (above);
  2. resolves it with `WhoService.resolveDetailed`;
  3. **`Resolved`, not signed in or the same identity**: runs the chain's session authentication
     strategy, saves through `WhoSessionSecurityContextRepository`, clears the
     `SecurityContextHolder` of the OAuth2 token, publishes `SignInSucceeded`, redirects to the
     success URL;
  4. **`Resolved`, a different identity is signed in**: invalidates the session and starts a new
     one (`changeSessionId` would carry the old identity's attributes), then as 3;
  5. **unknown or `NotLinked`**: starts a pending sign-up (below); redirects to the failure URL
     with `error=no-account`;
  6. **`Inactive` / `NoIdentity`**: `SignInFailed` (`INACTIVE_IDENTITY` / `UNKNOWN_CREDENTIAL`),
     redirect with `error=signin-failed`.
- Provider errors (cancel, state mismatch, bad token, issuer mismatch): `SignInFailed` with
  `PROVIDER_ERROR`, redirect with `error=signin-failed`.
- `SignInMethod` gains `OIDC`; `SignInFailureReason` gains `SIGN_UP_REFUSED` and
  `PROVIDER_ERROR`. Whether events carry the registration id as a field or only as a metric tag is
  the builder's call.

### Pending sign-up (Fable I2, I3)

Every callback for an account who does not know, signed in or not, ends here. Nothing is created
and nothing is linked until the person confirms.

- `PendingSignUp` in the session: registration id, issuer, subject, a display identity of the
  account (OIDC `email` or `preferred_username`; GitHub `login`) for the confirm screen, the
  sign-up request (below), **the identity signed in when it was created, or none**, an expiry
  (`who.oidc.sign-up-window`, default 10 minutes). Single use.
- It is **dropped when a different identity signs in** than the one recorded (including none →
  someone), so an account planted in a shared browser cannot be linked by the next person.
- `GET /who/oidc/pending` (JSON): provider, display identity, `canSignUp` (created signed out),
  `canLink` (created by the identity signed in now). `404` when none or expired.
- `POST /who/oidc/sign-up` (CSRF, JSON): only for a pending sign-up created signed out, by a
  browser still signed out. Runs the policy; `Allow` creates and signs in; `Refuse` is `403`.
- `POST /who/oidc/link` (CSRF, JSON): only for a pending sign-up created by the identity signed in
  now, within the recent-sign-in window (`who.oidc.link-window`, passkey's default). Links;
  publishes `CredentialAdded`; already linked to this identity is a no-op.
- Both POSTs are filters in who's chain, not MVC controllers, so they run the session strategy
  (sign-up signs in), as `PasskeyRegistrationGuardFilter` does.
- **To link**, a signed-in person navigates to `/oauth2/authorization/{id}`; the callback makes a
  linkable pending sign-up; the page shows "Link GitHub account *octocat*?" and confirms. There is
  no separate link endpoint and no link intent in the OAuth2 state.
- The guide **forbids auto-confirming a link**. An app may auto-confirm sign-up (one click) by
  calling `POST /who/oidc/sign-up` from its landing page.
- The guide recommends notifying the person on `CredentialAdded`, as for passkeys.
- An account already linked to another identity, reached while signed in: `error=already-linked`;
  nothing changes.
- Race: confirming sign-up reuses a `NotLinked` row, otherwise inserts the credential first; a
  unique violation rolls back and a new transaction resolves again, signing in to the identity that
  won. Nothing is published before the credential row is secured, so no event describes a
  rolled-back identity.

### Sign-up policy (Fable I5)

In `who-core`, so email links can use it later:

```java
public interface WhoSignUpPolicy {
  SignUpDecision decide(SignUpRequest request);
}

public record SignUpRequest(
    SignInMethod method,
    String registrationId,
    String issuer,
    String subject,
    Optional<String> verifiedEmail,
    Map<String, Object> claims) {}   // read-only; e.g. Google's hd, Entra's tid
```

- `ClosedSignUpPolicy`, public, `@ConditionalOnMissingBean`, refuses everything; an app's own
  policy may delegate to it.
- `verifiedEmail` is filled only when the registration is trusted for email
  (`who.oidc.providers.<id>.trust-email`: default true for Google and github.com, false for every
  other provider) **and** the provider marks it verified (OIDC `email_verified=true`; GitHub's
  primary-and-verified address).
- The guide says: allow-list an organization by its claim (`hd`, `tid`), never by email domain;
  a verified email outlives a person's employment.
- `Allow` may carry a setup callback the application runs in the same transaction (roles,
  profile); its shape is the builder's.

### GitHub's verified email (Fable I7)

Read in a delegating `OAuth2UserService`, which has the access token (the success handler does
not): `GET https://api.github.com/user/emails`, the entry with `primary` and `verified` true,
needing the `user:email` scope the guide tells apps to add. Short timeout; any failure means no
email, logged at DEBUG, never a failed sign-in. The token is not kept.

### URLs (Fable I9)

- `who.oidc.success-url` (default `/`) and `who.oidc.failure-url` (default `/`). Both must be local
  paths (start with `/`, not `//`, no scheme), checked at startup.
- The redirect is built with `UriComponentsBuilder`, adding `error=<code>` correctly to a URL that
  already has a query.
- Codes: `signin-failed` (opaque), `no-account`, `already-linked`. Events carry the exact reason.
- who does not make the failure URL public (consistent with 0.18's ruling for `link-base-url`); at
  startup it logs a WARN when the failure URL is not covered by `who.security.public-paths`, since
  a signed-out person would otherwise get a `401` there.

### Last-used method cookie

After every successful browser sign-in by any who method, who sets `who_last_sign_in` (`email-link`,
`passkey`, or the registration id): readable by scripts for a "Last used" badge, `Secure`,
`SameSite=Lax`, 30 days, nothing secret; sign-out leaves it.
`who.security.last-sign-in-cookie.enabled=false` turns it off.

### `who-jwt` must validate the audience (Fable I4, owner's decision)

Spring's resource server does not check `aud` by default, and now every social sign-up's
`(issuer, subject)` is also a bearer credential: an ID token Google issued to any other app for the
same person would resolve to the identity. `who-jwt` fails startup unless
`spring.security.oauth2.resourceserver.jwt.audiences` is set. An application that supplies its own
`JwtDecoder` bean owns that check; the guide says so. A breaking change in the upgrade notes.

### Configuration and activation

- `who-oidc` depends on Spring's OAuth2 client module and `who-federated`; the app adds only
  `who-oidc`.
- `WhoOidcAutoConfiguration` runs after Boot's `OAuth2ClientAutoConfiguration` and registers
  `OidcLoginMethod`, `ClosedSignUpPolicy` and the startup checks above, each
  `@ConditionalOnMissingBean`. who's default chain already orders before Boot's
  `OAuth2ClientWebSecurityAutoConfiguration` (0.18, M2), so its chain, with `oauth2Login` inside,
  replaces Boot's.

### One identity from browser and API: a caveat for the guide

A browser sign-in and an API call reach the same identity only when the provider uses the same
`iss` and `sub` in ID and access tokens. Keycloak and Auth0 do; Okta's access tokens may not
without configuration (to be checked); Google's access tokens are not JWTs. The guide states this
per provider.

### Native

Hints for what crosses the redirect in the session (`OAuth2AuthorizationRequest`) and for
`PendingSignUp`. A native test app, `who-native-tests/oidc`, signs in against a test provider,
signs up through an allowing policy, is refused by the default policy, and links.

### Docs

`docs/guides/social-login.md` (Google, GitHub, a generic OIDC provider; the policy; linking; the
email and issuer anti-goals stated plainly); upgrading notes (rename, table, `who-jwt` audience);
configuration reference; CHANGELOG; ROADMAP (Apple and multi-tenant Entra move to a later
release).

## Left to the builder

- `CredentialKind.JWT` → `FEDERATED` or not.
- The test provider for integration and native tests.
- The shape of `Allow`'s setup callback; metric and observation names.
- Whether events carry the registration id as a field.
