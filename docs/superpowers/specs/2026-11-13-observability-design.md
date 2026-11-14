# Observability: metrics and observations

Target: who 0.17.0, completing the observability theme in ROADMAP.md. The security events and the
DEBUG log line for failed sign-ins (`2026-11-13-security-events-design.md`) are already on `main`,
unreleased; this design builds on them and ships in the same release.

## Why

The events tell an application *what* happened, one occurrence at a time. Operators also need to
*watch* it: how many sign-ins fail and why, whether sign-in emails are going out, how long who's
own work takes, and which sign-in methods a running instance has on. They need it on the
dashboards and alerts they already have, which in a Spring Boot application means Micrometer and
Actuator.

Spring Security already times every sign-in: with an `ObservationRegistry` it records a
`spring.security.authentications` observation for email-link, code, passkey and API-key sign-ins
(who passes the authentication managers it builds through Spring's `ObjectPostProcessor`), and its
filter-chain observations cover JWT requests. What Spring cannot know is **why** a sign-in failed:
by design every failure is the same `401`. Only who knows the reason, and the reason is what an
operator alerts on: wrong codes rising is someone guessing codes, expired links rising is slow
mail, malformed requests rising is a broken client.

## Goals

- `SignInSucceeded` is published only for interactive sign-ins (email link, email code,
  passkey). API-key and JWT requests publish `SignInFailed` on every failed attempt but no
  `SignInSucceeded` (see "Event change" below).

- With a `MeterRegistry` present, counters for sign-ins (by method, outcome and **reason**),
  sign-in emails (by outcome), sign-outs, ended sessions and credential changes.
- Micrometer `Observation`s (a timer, and a span when tracing is on) around who's own work that
  Spring does not already observe: issuing a sign-in email and resolving a principal.
- Applications without Micrometer or Actuator pay nothing and see no change.
- A guide page with every meter, its tags and their values, plus example alerts.

## Non-goals

- Another sign-in timer or span. Spring Security's `spring.security.authentications` is the
  sign-in timer; the docs point to it.
- Gauges that need database queries (identities, active sessions, live API keys).
- Request details (IP address, user agent, path) as tags.
- A who-specific health indicator.
- The Actuator `info` contribution from the roadmap. Deferred to 0.18 (maintainer's decision):
  API-key and JWT sign-in turn on from a class check and the starter always brings them, so who
  cannot tell whether an application really uses them. It ships with 0.18's explicit per-method
  switches, when "on" means the switch is set.
- Exporters, dashboards or alerting configuration beyond examples in the guide.

## Anti-goals (failure even if it works)

- **Unbounded tags.** No address, identity id, credential id, label, role name or permission as
  a tag or a high-cardinality key value. Every tag value comes from a who enum.
- **Metrics that change behavior.** A failing meter registry or observation handler must
  never turn a sign-in into a `500`, abort an email, or change a response.
- **Breaking applications that construct who's services themselves.** Existing constructors keep
  working and mean "not observed".
- **A runtime dependency on Spring or Actuator in who-core.**

## Constraints

- who-core needs no Spring at runtime. It may depend on `io.micrometer:micrometer-observation`
  (decided with the maintainer: "the right way to do it", as in Nessy). No who-owned wrapper SPI
  around Micrometer, and no AOP or bean proxies (`@Observed`), which are a native-image risk.
- Spring Boot 4.1: metrics, observation and Actuator autoconfiguration live in separate modules
  (`spring-boot-micrometer-metrics`, `spring-boot-micrometer-observation`, `spring-boot-actuator`).
  who-autoconfigure's dependencies on them are optional.
- Native image must keep working; the native test bed (`who-native-tests`) is the proof.
- Events reach listeners after commit; sign-ins run without a transaction, so their events are
  delivered immediately. `SpringWhoEventPublisher` already catches and logs listener failures.
- Project rules: no suppressed warnings, explicit imports, tests at the level a user sees them,
  docs and CHANGELOG with every change.

## Approaches considered

- **A. Counters from the events, observations at who's own slow steps (chosen).** A listener in
  who-autoconfigure turns events into counters when a `MeterRegistry` exists; who-core and the
  email-link module call the Observation API directly, with `ObservationRegistry.NOOP` by default.
  The events already carry method, outcome and reason, so no sign-in code changes.
- **B. An Observation per sign-in carrying the reason.** Rejected: it times every sign-in a
  second time beside Spring's observation, nests duplicate spans in every trace, and needs each
  provider to hand the reason back up to wherever the observation starts.
- **C. who-core increments Micrometer counters directly.** Rejected: every service would need a
  `MeterRegistry`, so applications that build services by hand would have to supply one, and the
  counting would repeat the decisions the events already record.

## Design

### Event change: no success event per API request

Decided with the maintainer. Today `SignInSucceeded` fires on every request that carries a
valid API key or JWT. That is not a sign-in: an application that builds an audit log or "new
sign-in" notifications on who's events would get one per API call. Spring Security already
publishes `AuthenticationSuccessEvent` for each of those requests (Spring Boot registers a
`DefaultAuthenticationEventPublisher`; who's API-key manager uses it, and the resource server's
manager is Spring's own), so who's success event duplicates it with nothing added.

- API-key and JWT authentication no longer publish `SignInSucceeded`.
- `SignInFailed` is unchanged for every method: only who knows the reason, and per-request
  failures (unknown keys, missing claims) are what an operator alerts on.
- Email-link, email-code and passkey sign-ins keep `SignInSucceeded`: rare, and the event hands a
  listener the method, identity and credential without casting Spring's token.
- The events have not been released (0.17.0 is unreleased), so this is not a breaking change.
  The events guide, its `SignInSucceeded` description and the CHANGELOG entry change to match,
  and the guide points to Spring's `AuthenticationSuccessEvent` for per-request successes.
- `SignInMethod` keeps `API_KEY` and `JWT`; they appear on `SignInFailed`.
- ROADMAP 0.19's "last-used time" for API keys can no longer come from events; it needs its own
  mechanism (for example, a throttled update in the API-key provider). The roadmap says so.

### Counters (who-autoconfigure)

A `WhoMetrics` listener, registered when `MeterRegistry` is on the classpath and a bean exists,
receives `WhoEvent`s and increments these counters. Tag values are the enum constant in lower
case with `_` replaced by `-`, matching Spring Boot's style (`email-code`, `wrong-code`). Every
series of a meter has the same tag keys, as Prometheus requires.

| Meter | Event | Tags |
|-------|-------|------|
| `who.sign-ins` | `SignInSucceeded` (interactive methods only), `SignInFailed` (every method) | `method`; `outcome` = `success` or `failure`; `reason` = a `SignInFailureReason`, or `none` on success |
| `who.sign-in.emails` | `SignInEmailRequested` | `outcome` = an `EmailLinkOutcome` |
| `who.sign-outs` | `SignedOut` | none |
| `who.sessions.ended` | `SessionEnded` | `reason` = a `SessionEndReason` |
| `who.credentials.added` | `CredentialAdded` | `kind` = a `CredentialKind` |
| `who.credentials.revoked` | `CredentialRevoked` | `kind` |

At most 3 + 5 × 10 = 53 series for `who.sign-ins` (successes for 3 interactive methods with
`reason=none`, failures for 5 methods × 10 reasons). Per-request API-key and JWT volume comes from
Spring Security's `spring.security.authentications` and filter-chain metrics; the guide says so. Counters for every method/outcome/reason are
**not** pre-registered; a series appears the first time it happens.

Events from optional modules (`SignInEmailRequested` lives in who-email-link) are handled only
when that module is present, without a hard reference from classes that load without it.

Identity, enrollment-token and role events get no counters in this release: they are
administrative, rare, and better read from the audit trail the events already provide.

### Observations

who-core gains a compile dependency on `micrometer-observation`. Services that observe take an
`ObservationRegistry` through a new constructor; existing constructors delegate with
`ObservationRegistry.NOOP`. The autoconfiguration passes the application's registry when one
exists (`ObjectProvider.getIfAvailable(() -> ObservationRegistry.NOOP)`).

| Observation | Where | Low-cardinality key values |
|-------------|-------|----------------------------|
| `who.principal.resolve` | `WhoService.resolveDetailed` (which `resolve` delegates to; every caller in who uses it) | `outcome` = `resolved`, `inactive`, `not-linked`, `no-identity` |
| `who.sign-in.email.issue` | `EmailLinkIssuer.issue` (lookup, storing the code, the mail send) | `outcome` = an `EmailLinkOutcome` |

No high-cardinality key values (no ids, no addresses). Each observation has a contextual name
for spans (`who resolve principal`, `who issue sign-in email`).

`who.principal.resolve` runs on every sign-in, every JWT and API-key request, and every request
with a who session (`WhoSessionSecurityContextRepository.load` re-resolves the identity each
time), so it shows the per-request cost of loading the identity and its permissions. That is one
child span per authenticated request in a traced application, beside the filter-chain spans
Spring Security already creates; it is observed on every path on purpose (designer's call: the
per-request database cost is exactly what an operator needs to see). The resolve span nests
inside `who.sign-in.email.issue` when issuing an email. `who.sign-in.email.issue` includes the mail
send, which is the slowest and most failure-prone step in email sign-in.

A failure inside Micrometer (a throwing handler) is not caught by who for observations: Micrometer
already isolates handler failures from the observed code. The tests prove that a throwing handler
leaves `issue`'s outcome and `resolve`'s result unchanged; if they don't, who catches it.

### Docs and release

- New guide `docs/guides/observability.md`: the meter table, the observation table, Spring Security's sign-in observation, example PromQL alerts (code guessing,
  email send failures), and a reminder to protect the metrics endpoint. Linked from mkdocs nav and
  the events guide.
- CHANGELOG `[Unreleased]` Added entries; ROADMAP marks metrics and tracing done, moves the Actuator item to 0.18 and
  resolves the open question (the reason is a tag).
- Meter, tag and observation names are public API from 0.17.0, like the events.

## Testing

- API-key and JWT authentication publish no `SignInSucceeded` and still publish `SignInFailed`
  with the reason (the existing events tests change accordingly); email-link, code and passkey
  sign-ins still publish `SignInSucceeded`. The native API-key and JWT apps
  (`who-native-tests`) assert a success event today and change to assert there is none.

- `WhoMetrics` against a `SimpleMeterRegistry`: each event increments the right meter with the
  right tags; success carries `reason=none`.
- An autoconfiguration test with and without a `MeterRegistry` and an `ObservationRegistry`.
- A sign-in end to end (MockMvc, as the existing events tests do) shows the counter and the
  `who.principal.resolve` observation with the expected tags.
- `EmailLinkIssuer` and `WhoService` with a `TestObservationRegistry`: one observation per call,
  the expected outcome, nothing high-cardinality; a throwing handler changes nothing.
- The native test bed: one app (email link) gains Actuator and asserts a counter through the HTTP API, proving reflection-free wiring in a native binary.

## Left to the builder

- The exact class and bean names, and how `WhoMetrics` avoids loading optional event classes.
- Test helpers (`TestObservationRegistry` from `micrometer-observation-test`).
