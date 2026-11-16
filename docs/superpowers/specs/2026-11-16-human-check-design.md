# Human check on who's email endpoints

## Intent

Two who endpoints let anyone, signed out, make the application do something costly:

- `POST /who/email/send` emails an address. Anyone can make the application mail a known user
  over and over (mail bombing); the resend interval slows that per address, not across many.
- `POST /who/email/sign-in` with `{email, code}` tries a short code. Spraying codes across many
  addresses is the takeover risk the Email Links guide quantifies.

A bot check (Cloudflare Turnstile, hCaptcha and the like) in front of both makes automating either
expensive. who should let an application turn one on with a jar and a secret, and plug in any
vendor of its own. Email-link sign-up, designed next, builds on it.

## Goals

- A vendor-neutral `WhoHumanCheck` in who, applied to the two endpoints when a bean exists.
- `who-human-turnstile`: Cloudflare Turnstile, on with its jar, its secret key required.
- Safe by default where it matters: a configured check fails closed; tokens are checked for the
  endpoint (action) and optionally the hostname they were made for.
- The TypeScript client can send a token with each call.

## Non-goals and anti-goals

- No check on passkeys (cryptographic proof), provider sign-in (the provider has its own bot
  defences), API keys or JWTs (machine clients by design).
- No vendor widget or page code in who: the site key and widget belong to the application's page.
- Not a rate limiter: per-IP limits stay at the ingress, as the Email Links guide says.
- Anti-goal: a check that silently does nothing. A configured check must never be skippable by a
  missing header, a vendor outage (unless the application chose to fail open), or bean ordering.
- No change for applications that add no check.

## Approaches

1. **One module with a vendor property** (`who.human-check.vendor=turnstile`): one jar, but every
   vendor's settings and code ship to everyone, and it breaks who's rule that a jar turns a
   module on.
2. **A default no-op bean that vendor modules override:** if auto-configuration order ever slips,
   the no-op wins and the check silently never runs: the anti-goal above.
3. **Chosen: a core interface, no default bean, one module per vendor.** No bean, no filter. A
   vendor jar contributes the bean; an application's own bean always wins. Matches module
   activation (jar on means module on; missing required configuration fails startup).

## Design

### Core (who-spring-security)

```java
@FunctionalInterface
public interface WhoHumanCheck {
  /** Allows everyone; for tests that turn the feature on without a vendor. */
  WhoHumanCheck ALLOW_ALL = request -> true;

  boolean passes(HumanCheckRequest request);
}

public record HumanCheckRequest(Optional<String> token, String clientIp, String action) {}
```

- `token`: the `Who-Human-Check` request header, absent when the page sent none. Optional so an
  adaptive check can pass low-risk traffic without a widget.
- `clientIp`: `request.getRemoteAddr()`, which is the client once Spring's forwarded-header
  handling is configured (`server.forward-headers-strategy`); the docs say so.
- `action`: `email-send` or `email-sign-in`.
- Implementations never see the servlet request, so none can consume the JSON body who reads.
- The header name is fixed: `Who-Human-Check`, beside `Who-Error`.

`HumanCheckFilter` (package-private) matches only `POST <root>/email/send` and
`POST <root>/email/sign-in`, builds the request, and on `false` hands
`HumanCheckFailedException` to the application's exception handling (`403`, `Who-Error:
human-check-failed`, a `WhoErrorCode`). The endpoint never runs: no email, no code tried. An
exception from the check is a failure (closed), logged at WARN with the exception class only.

`EmailLinkLoginMethod.configure` adds the filter when a `WhoHumanCheck` bean exists: after
`CsrfFilter` (a forged request costs no vendor call) and before Spring's one-time-token filters.
A later method with public endpoints can do the same.

The startup line names the check when there is one:
`who: this chain signs browsers in with email-link, passkey under /who, human check: turnstile`,
from a defaulted `WhoHumanCheck.name()` like the sign-in methods'.

### who-human-turnstile

A new module, its own auto-configuration, on with its jar:

```yaml
who:
  turnstile:
    secret-key: ${TURNSTILE_SECRET}   # required; startup fails naming the property without it
    hostnames: [app.example.com]      # optional; a token made for another hostname fails
    timeout: 3s                       # optional, default 3s
    fail-open: false                  # optional; true lets requests through when Cloudflare
                                      # cannot be reached or answers an error
```

- `TurnstileHumanCheck implements WhoHumanCheck`, `name()` is `turnstile`.
- A missing token fails without calling Cloudflare.
- Calls `https://challenges.cloudflare.com/turnstile/v0/siteverify` with Spring's `RestClient`
  (form post: `secret`, `response`, `remoteip`), no new dependencies.
- Passes only when `success` is true, the answer's `action` equals the request's action, and, with
  `hostnames` set, the answer's `hostname` is one of them. Single use is Cloudflare's to enforce.
- Timeouts, connection failures and non-2xx answers fail, unless `fail-open`.
- `@ConditionalOnMissingBean(WhoHumanCheck.class)`: an application's own check wins.
- Native-image hints for the verify answer's JSON.
- Never logs the token or the secret.

### TypeScript client

- `email.send(address, { humanCheck?: string })` returns
  `WhoResult<void, "human-check-failed">` instead of `Promise<void>` (breaking; the client is
  0.19.0).
- `email.signIn(credentials, { humanCheck?: string })` adds `"human-check-failed"` to its reasons.
- The option sets the `Who-Human-Check` header for that one call; tokens are single use, so there is
  no client-wide setting.

### Docs and examples

- A guide section "Human check" (in Email Links, or a short page of its own): what it guards, the
  Turnstile module, writing your own check, failing open or closed, forwarded headers, the action
  names to give the widget (`email-send`, `email-sign-in`).
- One browser example shows Turnstile with Cloudflare's published test keys, which always pass.
- CHANGELOG (Added; Breaking for the client), README modules row, BOM, ROADMAP.

## Testing

- Core: the filter runs only on the two endpoints; missing bean adds no filter; `false` and an
  exception both refuse before the endpoint; a passing check reaches it; CSRF still comes first.
- Turnstile: against a local stub of siteverify (never Cloudflare in CI): success, failure,
  wrong action, wrong hostname, timeout, 5xx, fail-open, missing token, missing secret fails
  startup.
- Client: header sent only when given; the new result reasons.

## Left to the builder

- Package of `HumanCheckFilter` and the exception; the guide's placement.
- How the stub verify server is built in tests.
- Exact Javadoc and log wording.
