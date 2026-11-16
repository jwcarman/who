# `@RequiresRecentSignIn`

## Intent

A stolen session cookie can mint a long-lived API key today: nothing makes making a key, or any
other sensitive action, ask for a recent sign-in. who already enforces recency in three places of
its own (adding an email address, linking a provider account, registering a passkey), each with
its own filter. Applications have no way to ask for it on their own endpoints.

`@RequiresRecentSignIn` lets them: put it on a controller or service method, and the request must
come from a person who signed in within `who.security.recent-sign-in` (10 minutes by default). A
person whose sign-in is older is told to sign in again; a caller that cannot sign in again is
refused.

## Goals

- `@RequiresRecentSignIn` on methods and types, next to `@RequiresPermission`, with an optional
  window of its own: `@RequiresRecentSignIn(within = "5m")`.
- The same rule for URL authorization: an `AuthorizationManager` an application can use in
  `requestMatchers(...).access(...)`.
- One rule for every kind of caller:

  | Caller | Signed in recently | Signed in too long ago | No sign-in time |
  |---|---|---|---|
  | Browser session | allowed | `401` `ReauthenticationRequiredException` (as today) | — |
  | JWT with `auth_time` | allowed | `401` with the RFC 9470 challenge | — |
  | JWT without `auth_time` | — | — | `403`, naming the missing claim |
  | API key | — | — | `403`: a key has no sign-in to be recent |

- Built on Spring Security 7's factor model (`FactorGrantedAuthority`, `RequiredFactor`,
  `AllRequiredFactorsAuthorizationManager`), so an application that later uses Spring's own
  multi-factor features finds who's sign-ins where Spring expects them.
- who's three recency filters use the same check, so there is one rule, not four copies.
- The examples protect key-making with it and show signing in again: web-and-api's Keys page and
  key API, the API example's key endpoints (with Keycloak step-up), the observability example's
  `/keys`.

## Non-goals

- Stronger kinds of sign-in (RFC 9470's `acr_values`, "must have used a passkey"). Any sign-in
  counts, as decided for recency elsewhere in who.
- Microsoft Entra's claims challenge (`insufficient_claims`) and authentication contexts.
- A configurable claim name for providers without `auth_time` (Auth0). Later, if asked.
- Annotating who's own services. `ApiKeyService.create` stays callable by setup code and jobs with
  nobody signed in; the annotation belongs on the application's endpoints.

## Anti-goals

- An API key ever counting as recent. Spring stamps its bearer factor with the time of the
  request, which is right for what it means (a token was presented now) and wrong for "a person
  signed in recently". who must never do that for keys or tokens.
- A JWT without `auth_time` failing anywhere except on endpoints that ask for a recent sign-in.
  Nothing else changes for JWT sign-in.
- A step-up challenge the client cannot satisfy: a token without `auth_time` gets a `403`, since
  signing in again would bring another token without it.

## Approaches

1. **Spring Security 7's factors (chosen).** who stamps a `FactorGrantedAuthority` with the time
   the person signed in; the annotation checks it with Spring's `RequiredFactor` and
   `AllRequiredFactorsAuthorizationManager`. Idiomatic, and lines up with Spring's MFA.
2. **A who-only check of a `signedInAt` field on the token.** Smaller, but a rule of who's own
   beside Spring's, which an application using Spring's MFA would have to reconcile.
3. **Filters by URL, like who's own three.** Already four copies of the rule; does not reach
   service methods.

The owner chose to include JWTs from the start after research into what identity providers put in
access tokens: Keycloak (tested: `auth_time` in access tokens, unchanged on refresh), Okta and
Cognito include it; Entra only as an optional claim; Auth0 not in access tokens; Google's access
tokens are not JWTs.

## The design

### Stamping the sign-in

`WhoAuthenticationToken` gains its sign-in factor: a `FactorGrantedAuthority` whose `issuedAt` is
when the person signed in, among its authorities, so `getAuthorities()` shows it beside the
permissions.

- **Sessions.** `WhoSessionSecurityContextRepository` rebuilds the token from `WhoSession` on
  every request; it stamps the factor at `WhoSession.signedInAt()`. The factor's name follows the
  kind of credential the session signed in with: an email credential `FACTOR_OTT`, a passkey
  `FACTOR_WEBAUTHN`, a provider account `FACTOR_AUTHORIZATION_CODE`. The session does not store
  the method today; the credential's kind gives it without changing the session format.
- **JWT.** `WhoJwtAuthenticationConverter` stamps `FACTOR_BEARER` at the token's `auth_time` when
  the claim is present, and no factor when it is absent. (Spring's own converter stamps
  `FACTOR_BEARER` at the time of the request; who's never does.)
- **API keys.** No factor.
- **Sign-in requests themselves** (email link, code, passkey, provider) stamp the factor at the
  moment of sign-in, so the session saved from them carries the same time.

### Checking it

- `@RequiresRecentSignIn` (in `who-spring-security`, beside `@RequiresPermission`):
  `ElementType.METHOD`, `TYPE`, `ANNOTATION_TYPE`; `String within() default ""` (a Spring Boot
  duration such as `5m`; empty means `who.security.recent-sign-in`).
- Enforced by a method interceptor registered with who's method security
  (`who.method-security.enabled`), not by SpEL: an
  `AllRequiredFactorsAuthorizationManager.anyOf(...)` over who's factors, each with the window as
  `validDuration`. A method annotated with both runs the permission check and the recency check.
- `WhoAuthorization.recentSignIn()` and `recentSignIn(Duration)`: the same manager for URL rules.
- The decision tells expired from missing (`RequiredFactorError.Reason`), and the token's sign-in
  method tells a session from a JWT from an API key; together they choose the answer in the table
  above.

### Answering

- **Session, expired:** `ReauthenticationRequiredException`, `401`, the problem who already sends
  when a credential is added late. Pages handle one answer everywhere: sign in again, retry.
- **JWT, expired:** `401` with
  `WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description="A more
  recent sign-in is required", max_age=<window in seconds>`, through who's stateless challenges
  (`StatelessAnswers`), and a problem body like who's other `401`s.
- **JWT without `auth_time`, API key:** `403` problems of their own, saying what is missing ("this
  token has no auth_time claim; configure your identity provider to include it", "an API key
  cannot make this request: it needs a recent sign-in by a person").
- Each refusal logs at DEBUG and publishes nothing new: no sign-in failed.

### who's own filters

`EmailAddressFilter`, `LinkStartFilter`, `PasskeyRegistrationGuardFilter` and the sign-up link
check in `PendingSignUpFilter` use the same check, keeping their answers.

### Documentation and examples

- A "Recent sign-in" section in the security-model guide; `errors.md` rows for the new answers;
  the JWT guide on `auth_time`, step-up with `max_age`, and which providers include the claim;
  configuration and upgrading notes; CHANGELOG.
- web-and-api: annotate key-making (page and API); its page handles the `401` by sending the
  person to sign in and back. API example: annotate key-making, README walks through step-up
  against Keycloak (`max_age` on the authorization request). Observability: annotate `/keys`.

### Tests

- Sessions: within the window allowed; outside it `401` re-authentication; `within` overrides the
  window; the window boundary.
- JWT: recent `auth_time` allowed; old `auth_time` `401` with the exact challenge header; no
  `auth_time` `403`; a JWT without `auth_time` still reaches an unannotated endpoint.
- API key: `403` on an annotated endpoint, allowed elsewhere.
- Type-level annotation; meta-annotation; with `@RequiresPermission` (both enforced); URL rule.
- who's three filters keep their behaviour.
- The factor on a session token is issued at `signedInAt`, never at the time of the request.

## Left to the builder

- Exception class names for the two `403`s and the JWT `401`.
- How the credential's kind is found when rebuilding the session token.
- Clock skew on `auth_time` (a value slightly in the future is treated as now).
- The example pages' "sign in again" flow details.
