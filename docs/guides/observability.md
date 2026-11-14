# Observability

who counts what happens as Micrometer meters and times its own work as Micrometer observations,
so sign-ins, failures and sign-in emails show up on the dashboards and alerts you already have.
There is nothing to configure in who: add Spring Boot Actuator (or any `MeterRegistry` and
`ObservationRegistry` beans) and it starts.

## What Spring Security already measures

Spring Security times every authentication: with an `ObservationRegistry` it records
`spring.security.authentications` for email-link, email-code, passkey and API-key sign-ins, and
its filter-chain observations cover JWT requests. who does not time sign-ins a second time.

What Spring cannot see is **why** a sign-in failed: by design every failure answers with the same
`401`. who's meters add that reason.

## Meters

Counters, registered when the application has a `MeterRegistry`. Tag values are who's enum
constants in lower case with `_` replaced by `-`. No address, identity id, credential id, label
or role is ever a tag. A series appears the first time it counts.

| Meter | Counts | Tags |
|---|---|---|
| `who.sign-ins` | Sign-in attempts | `method`, `outcome`, `reason` |
| `who.sign-in.emails` | Sign-in email requests | `outcome` |
| `who.sign-outs` | Sign-outs of who sessions | none |
| `who.sessions.ended` | Sessions who ended | `reason` |
| `who.credentials.added` | Credentials added | `kind` |
| `who.credentials.revoked` | Credentials revoked | `kind` |

Tag values:

- `method`: `email-link`, `email-code`, `passkey`, `api-key`, `jwt`.
- `outcome` on `who.sign-ins`: `success` or `failure`.
- `reason` on `who.sign-ins`: `none` for a success; for a failure, `malformed`, `invalid-link`,
  `no-live-code`, `wrong-code`, `code-already-used`, `invalid-assertion`, `unknown-key`,
  `missing-claims`, `unknown-credential` or `inactive-identity`.
- `outcome` on `who.sign-in.emails`: `sent`, `unknown-address`, `inactive-identity`,
  `resend-suppressed`, `send-failed`.
- `reason` on `who.sessions.ended`: `identity-inactive`, `credential-revoked`.
- `kind`: `email`, `passkey`, `api-key`, `jwt`, `other`.

Successes count only real sign-ins (email link, email code, passkey). API keys and JWTs
authenticate every request, so they count only failures here; Spring Security's
`spring.security.authentications` counts every authenticated API-key request. `who.sign-ins`
has at most 53 series.

The meters are built from who's [events](events.md), so they count exactly what the events
report.

## Observations

| Observation | Spans | Key |
|---|---|---|
| `who.principal.resolve` (`who resolve principal`) | Loading a credential's identity and its permissions | `outcome`: `resolved`, `inactive`, `not-linked`, `no-identity`, or `error` when the lookup throws (a database fault) |
| `who.sign-in.email.issue` (`who issue sign-in email`) | Looking up the address, storing the code and sending the mail | `outcome`: as `who.sign-in.emails`, or `error` if issuing throws |

With Actuator each observation is a timer of the same name, and a span when tracing is on. The
resolution runs on every authenticated request (sessions, API keys and JWTs re-resolve the
identity each time), so its timer shows the per-request cost of who's database lookups. When a
sign-in email is issued, the resolution span nests inside the issue span.

A failing observation handler never changes who's answer: who logs it at WARN, with the
exception class only, and carries on.

## Building services yourself

The autoconfiguration passes your `ObservationRegistry` to `WhoService` and `EmailLinkIssuer`. If
you construct them yourself, use the constructors that take an `ObservationRegistry`; the
existing constructors observe nothing.

## Example alerts

PromQL, with the Prometheus registry's naming:

```promql
# Someone is guessing sign-in codes
sum(rate(who_sign_ins_total{outcome="failure", reason="wrong-code"}[5m])) > 1

# Sign-in emails are not going out
sum(rate(who_sign_in_emails_total{outcome="send-failed"}[15m])) > 0

# Someone is trying API keys
sum(rate(who_sign_ins_total{method="api-key", reason="unknown-key"}[5m])) > 1
```

## Protect the metrics endpoint

The meters reveal sign-in volume and failure patterns. Do not expose `/actuator/prometheus` or
`/actuator/metrics` without authentication.
