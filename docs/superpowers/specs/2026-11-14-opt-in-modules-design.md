# Add a jar and go: opt-in modules and a default security chain (0.18.0)

## Intent

Today `who-spring-boot-starter` brings every module, so API keys and JWT are always on, while
email link and passkey stay silently off until a property is set. Neither is how a Spring Boot
library is expected to behave. The goal, in the user's words: "ideally, it's just add the jar to
the classpath and go, it wires itself in automatically."

## Rules (decided)

1. **A jar on the classpath turns its module on.** No property ever turns anything on.
2. **A property can only turn a module off:** `who.<module>.enabled=false`, missing means `true`
   (`@ConditionalOnBooleanProperty(matchIfMissing = true)`), like the existing
   `who.method-security.enabled`.
3. **Required configuration with no safe default fails startup**, with a message naming the
   property. A module is never silently off.
4. **A module wires itself into the app's security** when the app has not written a
   `SecurityFilterChain`. Approach A below.

## Goals

- The starter is the base only; each sign-in method is one added dependency.
- With no `SecurityFilterChain` of the app's own, every method on the classpath works with no
  security code.
- Apps that write their own chain mount every method with one line.
- Apps already on who keep working after following a short upgrade section.

## Non-goals

- New sign-in methods, new properties beyond those below, or changes to how any method behaves
  once mounted.
- Per-method starters (`who-email-link-spring-boot-starter` …): each module's pom already carries
  its third-party dependencies, so the module jar is the unit to add.

## Anti-goals

- who changing a `SecurityFilterChain` the application wrote (approach C). An app's own security
  rules are never edited behind its back.
- Deriving a security-relevant value from the request: `link-base-url` and `rp-id` never come
  from the `Host` header (link poisoning, passkeys bound to an attacker's domain).
- A module that is on the classpath but quietly does nothing.

## Approaches

- **A. One default chain with every method (chosen).** Session sign-in for email link and
  passkey; API keys and JWTs act only on requests that carry their header, which are stateless
  and exempt from CSRF. Backs off when the app defines any `SecurityFilterChain`. Costs: one
  chain mixes browser and API rules, so the boundary between them needs exact tests.
- B. Two default chains split by a path property (`/api/**`). Simpler chains, but invents a URL
  convention and silently ignores a key sent elsewhere.
- C. who customises every chain the app writes. Most automatic; rejected as an anti-goal.

## Design

### Modules and the starter

- `who-spring-boot-starter` depends on `who-core`, `who-autoconfigure`, `who-spring-security`,
  `who-jdbc` and `who-rbac` only: identities, sessions, permissions, no sign-in method.
- Added individually: `who-email-link`, `who-passkey`, `who-apikey`, `who-jwt`,
  `who-enrollment`. All are managed by `who-bom`.

### Activation per module

| Module | On when | Off with | Required, else startup fails |
|---|---|---|---|
| email link | jar present | `who.email-link.enabled=false` | `link-base-url`, `code-secret` (≥ 32 chars), and a sender: an `EmailLinkSender` bean, or `spring.mail.host` plus `who.email-link.from` |
| passkey | jar present | `who.passkey.enabled=false` | `rp-id` |
| API key | jar present | `who.api-key.enabled=false` | none |
| JWT | jar present | `who.jwt.enabled=false` | a `JwtDecoder` (Boot makes one from `spring.security.oauth2.resourceserver.jwt.issuer-uri` or `jwk-set-uri`) |
| enrollment | jar present | `who.enrollment.enabled=false` | none |

The checks that exist today (`link-base-url`, `code-secret`, `code-length`, `code-max-attempts`,
`from`, sender, `rp-id`) stay as they are; they only stop being hidden behind
`@ConditionalOnProperty`. A failure is reported through a `FailureAnalyzer`, so the startup
output reads like Boot's own: what is missing, and what to set.

### The default chain

Registered only when the app defines no `SecurityFilterChain` (Boot's
`@ConditionalOnDefaultWebSecurity`) and it is a servlet web app; it replaces Boot's default
form-login chain. It is `http.with(WhoSecurity.all(), …)` plus:

- every request needs a signed-in identity, except the public paths below;
- `who.security.public-paths` (list, default empty) for the app's own pages and assets, such as
  `/signin`, `/`, `/assets/**`. *My call:* without it an app's sign-in page would itself need a
  sign-in, which defeats "add the jar and go". It is configuration, not an on-switch.

### `WhoSecurity.all()`

A new configurer in `who-spring-security`, for apps that write their own chain:
`http.with(WhoSecurity.all(), Customizer.withDefaults())`. It applies, for whatever is on the
classpath:

- browser methods: what `WhoWebSecurity.passwordless()` does today (CSRF `spa()`, who's session
  repository, no request cache, exception hand-off) and every `PasswordlessLoginMethod`;
- header methods: the API-key filter and the JWT resource server with
  `WhoJwtAuthenticationConverter`;
- CSRF ignores requests that carry `X-API-Key` (or the configured header name) or
  `Authorization: Bearer`. Browsers cannot attach those headers cross-site, so they cannot be
  forged; such requests keep a stateless security context and never create a session;
- each method's public paths: email link `/ott/generate`, `/login/ott`; passkey
  `/webauthn/authenticate/options`, `/login/webauthn`; plus the path of `link-base-url`.
  *My call:* each `PasswordlessLoginMethod` declares its public paths (a new default method
  returning none), so a new method brings its own.

Header methods are found the same way browser methods are: as beans of a small interface that
the module's autoconfiguration registers. `who-spring-security` never references `who-apikey`
or `who-jwt` classes.

`WhoWebSecurity.passwordless()` and `WhoApiKeySecurity.apiKey()` stay, for apps that split
browser and API chains themselves.

### Configuration split

`WhoAutoConfiguration` (980 lines) becomes one autoconfiguration class per module (core,
email link, passkey, API key, JWT, enrollment, web security, metrics), all still in
`who-autoconfigure`, ordered with `after`. *My call:* it is done in this release because every
module's conditions change anyway.

## Upgrading (breaking)

- Apps on the starter add the modules they use. A starter app that used everything adds four or
  five dependencies.
- Apps with their own `SecurityFilterChain` see no change in behaviour.
- Apps with `who-passkey` or `who-email-link` on the classpath but not configured now fail
  startup with a named property: remove the dependency or set `enabled=false`.
- Apps without a chain of their own get who's chain instead of Boot's form login.
- `docs/guides/upgrading.md` gets a 0.18.0 section with the dependency list per method; the
  getting-started guide shows the add-a-jar flow; ROADMAP and CHANGELOG are updated.

## Tests that pin the design

- For each module: on with the jar alone, off with `enabled=false`, startup failure naming each
  required property, and the `FailureAnalyzer` message.
- The starter's dependency tree contains no sign-in module.
- Default chain: present without an app chain, absent with one; Boot's form login is gone.
- The CSRF boundary: a cookie-authenticated POST without a token gets `403`; the same POST with a
  valid API key or bearer token is accepted; a request with an API-key header never receives a
  session cookie; a bogus `Authorization: Bearer` header does not bypass CSRF for a session
  request (it fails as a bearer request, `401`).
- Public paths: each method's own paths and `link-base-url`'s path are reachable signed out; all
  else is `401`.
- The native test bed: each app drops its own chain where the default now covers it, and runs
  natively (AOT must handle the conditional chain).

## Left to the builder

- Names of the header-method interface and the `FailureAnalyzer`.
- Exact `after` ordering between the split autoconfigurations and Boot's security
  autoconfiguration.
- Whether `public-paths` match with Spring's `PathPatternRequestMatcher` (expected).
