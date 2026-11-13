# Security Model

What who protects against, what it leaves to your application, and what it assumes. The details
live in each guide; this page collects them so you can review who as one system. To report a
vulnerability, see the [security policy](https://github.com/jwcarman/who/blob/main/SECURITY.md).

## Trust boundaries

who runs inside your application and draws these lines:

| Boundary | What crosses it | Treated as |
|----------|-----------------|------------|
| Browser or API client → who's endpoints and filters | sign-in bodies, typed codes, link tokens, passkey assertions, API-key headers, session cookies | untrusted: bounded and validated before any database call, and fuzzed |
| JWT issuer → `JwtDecoder` → who | a validated token's issuer and subject | trusted only after your decoder checks the signature, issuer and audience |
| Mailbox → person | the link and code in a sign-in email | as trustworthy as the mailbox (see below) |
| Your application code → who's services | calls to `EmailCredentialService`, `RbacService`, `ApiKeyService` and the rest | trusted: your code decides who may call them |
| who → database | identities, credentials, roles, hashed secrets | trusted for integrity; read access alone reveals no live secret |
| Configuration → who | `who.*` properties, including `code-secret` | trusted, and checked at startup |

## What who protects

**Secrets are stored as hashes, never as values.**

- Sign-in link tokens and enrollment tokens are 256-bit random values, stored as SHA-256 hashes.
- API keys are `who_` plus 256 random bits, stored as SHA-256 hashes and shown once at creation
  ([API Keys](api-keys.md)).
- Typed sign-in codes are stored as HMAC-SHA256 under `who.email-link.code-secret`, keyed with
  the credential id as well. The secret is not in the database, so a copy of the database alone
  cannot be used to recover a live code ([Email Links](email-links.md#code-secret)).

**Signing in by email reveals nothing about who is registered.**

- `POST /ott/generate` always answers `202`, registered or not, and sends mail after answering.
- Every failed sign-in is the same `401`: an unknown address, a wrong, burnt or expired code, a
  used or expired link, an inactive identity and a malformed body cannot be told apart.
- When an address has no live code, who still computes a dummy HMAC, so a wrong guess costs about
  the same work whether or not the address is registered. A small timing difference remains (a
  database write or not), which the rate limits below cover.

**Guessing a code is bounded.**

- A code allows `code-max-attempts` guesses (5 by default). The attempt is counted with a single
  atomic update, so concurrent requests cannot exceed the limit, and only one of two correct
  submissions signs in.
- Each address has at most one live link and code, and at most one email per resend interval
  (60 seconds by default). Link and code expire together (5 minutes by default) and work once;
  using one ends the other.
- [How strong is the code](email-links.md#how-strong-is-the-code) works through the numbers.

**Passkeys are phishing-resistant.** who requires user verification and discoverable
credentials, and checks the origin and relying party id. Adding a passkey requires a recent
sign-in, within `who.passkey.registration-window` (10 minutes by default) ([Passkeys](passkeys.md)).

**Sessions are hardened for single-page apps.** `WhoWebSecurity.passwordless()`:

- turns on CSRF protection (`csrf.spa()`) and refuses to start on a chain without it, or on a
  stateless chain;
- changes the session id and rotates the CSRF token at sign-in;
- keeps only ids in the session, and re-checks the identity and credential on every request, so
  deactivating an identity or revoking a credential takes effect on the next request
  ([Sessions](sessions.md));
- answers `401` and `403` instead of redirecting, saves no requests, and sets a `no-referrer`
  policy.

**Permissions fail closed.** Method security is on by default, and `@RequiresPermission` denies
anything the identity's resolved permissions do not grant ([RBAC](rbac.md)).

**Input is bounded.** JSON bodies on the sign-in endpoints are capped at 8192 bytes, addresses
at 320 characters with no control characters, and typed codes at 64 characters, all before any
database call. The code that parses these inputs is fuzzed with Jazzer every week, and every
input that ever broke it is replayed as a test in every build.

**Misconfiguration fails at startup.** Missing or weak settings stop the application instead of
running insecurely: a code secret shorter than 32 characters or left as an unresolved
`${...}` placeholder, a disabled CSRF configuration, a stateless passwordless chain, and the other
checks listed in each guide.

## Audit trail

who publishes a [public event](events.md) for every sign-in, with a server-side reason when it
fails, for sign-out and ended sessions, and for every change to identities, credentials,
enrollment tokens and roles. The client never sees the reason: every failure is still the same
`401`. who does not store events. To meet a requirement such as OWASP ASVS chapter 7, write a
listener that records them where your logs and alerts live.

Events never carry link tokens, codes, API keys, enrollment token values or hashes. Some carry an
email address or a user-supplied label (a passkey's); treat both as personal data and as untrusted
text.

**Logging rule.** who's own logs never contain keys, codes or tokens, at any level. Email
addresses appear only at DEBUG, in the one line each failed sign-in writes there (method, reason,
and the identity or credential id when known). At INFO and above there are none. Third-party
loggers are outside who's control: at TRACE, the PostgreSQL JDBC driver and Spring JDBC can log
query parameters, which include an API key's hash. Do not enable TRACE for them in production.

## Design principles

How who applies the classic secure design principles (Saltzer and Schroeder):

- **Fail-safe defaults.** Method security is on, CSRF is required, unknown credentials are denied
  rather than provisioned, and weak or missing settings stop startup.
- **Complete mediation.** The session holds only ids; the identity and credential are re-checked
  on every request.
- **Least privilege.** `@RequiresPermission` grants nothing the resolved permissions don't
  include; API keys and sessions map to the same identity and the same permissions.
- **Economy of mechanism.** Sign-in is built on Spring Security's one-time-token and WebAuthn
  support, not a parallel implementation; every credential type ends in one `WhoPrincipal`.
- **Separation of privilege.** A typed code needs both the stored HMAC and the secret that is not
  in the database.
- **Open design.** Nothing depends on the code being secret; the source and this document are
  public.
- **Psychological acceptability.** No passwords to choose or reuse; uniform answers mean
  applications can't leak account existence by accident.

## Common weaknesses and how they are countered

| Weakness | Countered by |
|----------|--------------|
| Account enumeration (CWE-204, CWE-208) | uniform `202` and `401` answers; a dummy HMAC when no code exists |
| Brute-forcing codes (CWE-307) | per-address attempt limit, counted atomically; expiry; ingress rate limits |
| Session fixation (CWE-384) and CSRF (CWE-352) | session id and CSRF token rotated at sign-in; CSRF required |
| Stolen database contents (CWE-256, CWE-312) | tokens, codes and API keys stored only as hashes or keyed HMACs |
| Weak randomness (CWE-330) | every secret from `SecureRandom`, 256 bits for tokens and keys |
| Injection and malformed input (CWE-20, CWE-89) | parameterized SQL only; allowlisted, size-capped input, fuzzed with Jazzer |
| Open redirects (CWE-601) | no redirects: sign-in answers `401` and `403` directly |
| Insecure configuration (CWE-1188) | startup checks for the code secret, CSRF and session settings |
| Race conditions (CWE-362) | single-statement attempt counting and single-use redemption |

Static analysis (CodeQL, SonarCloud) runs on every change, and fuzz tests replay every input
that ever broke the parsers.

## What your application must do

- **Rate limits at the ingress.** who limits guesses per address, not per client, and never
  reads `X-Forwarded-For`. Put per-IP limits on `/ott/generate`, `/login/ott`,
  `/webauthn/authenticate/options` and `/login/webauthn` at the load balancer, WAF or ingress
  ([Rate limiting at the ingress](email-links.md#rate-limiting-at-the-ingress)). Limit request
  body sizes there too.
- **TLS everywhere**, and secure session cookies (`Secure`, `HttpOnly`, `SameSite=Lax`)
  ([Session cookie](email-links.md#session-cookie)).
- **Keep the code secret secret.** Generate it with `openssl rand -base64 32`, keep it out of
  source control, use the same value on every instance, and don't expose it through Actuator.
- **Validate JWTs.** who maps a token's issuer and subject to an identity; your `JwtDecoder` is
  what checks the signature, issuer and audience ([JWT](jwt.md)).
- **Verify an address before registering it.** `EmailCredentialService.register` trusts that you
  have; invitations through [Enrollment](enrollment.md) are the usual way.
- **Don't run `/login/ott` inside a transaction.** The attempt count must commit even when a
  sign-in fails.
- **Watch for abuse.** Alert on volumes of failed sign-ins and sign-in emails, using the
  [events](events.md).
- **Answer errors safely.** who throws and your application formats the response
  ([Errors](errors.md)); keep failure responses as uniform as who's.

## What who assumes

- **The mailbox belongs to the person.** Email sign-in is as strong as the account behind the
  address. Anyone who can read the inbox can sign in.
- **The database is trusted for integrity.** Someone who can write to who's tables can grant
  themselves roles or credentials. Read access alone does not reveal tokens, codes or API keys.
- **One relying party per host.** The passkey `rp-id` belongs to this application and is not
  shared with other apps on the same domain.
- **Spring Security and the JDK are correct.** who builds on Spring Security's one-time-token and
  WebAuthn support and on the JDK's `SecureRandom`, `MessageDigest` and `Mac`.

## Not in scope

who has no passwords, no SMS codes, and is not an OAuth authorization server. It does not bind a
sign-in code to the browser that asked for it: a code typed on another device works, which is the
point of the code.
